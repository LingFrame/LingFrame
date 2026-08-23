package com.lingframe.starter.resource;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebRouteResolution;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.ServletWebRequest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 灵元容器卸载回归测试。
 * <p>
 * 完全走生产的真实路径：
 * 1. 通过 {@code LingManifestLoader.parseDefinition()} +
 * {@code lifecycleEngine.deploy()} 部署灵元
 * 2. 通过 {@code lifecycleEngine.undeployWithReport()} 卸载灵元
 * 3. 验证 ClassLoader 能被 GC 回收
 * <p>
 * GC 失败时的线程/Bean 诊断与 heap dump 统一委托 {@link ClassLoaderLeakDiagnoser}
 * （不要再把诊断逻辑散回本测试类）。
 */
@Slf4j
@SpringBootTest(classes = LingTestSpringConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "lingframe.dev-mode=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("SpringLingContainer 卸载回归测试")
class SpringLingContainerUnloadRegressionTest {

    private static final String LING_ID = "test-ling";
    private static final String LING_VERSION = "1.0.0";
    private static final String APP_CLASS_NAME = "sample.springling.SampleLingApp";

    @Autowired
    private LingLifecycleEngine lifecycleEngine;

    @Autowired
    private EventBus eventBus;

    @Autowired
    private WebInterfaceManager webInterfaceManager;

    @Autowired
    private ApplicationContext coreApplicationContext;

    @Test
    @DisplayName("通过开发部署-卸载链路应回收 ClassLoader")
    void shouldReleaseClassLoaderThroughDevelopmentDeployUndeployPath() throws Exception {
        // 不再硬编码绝对路径和版本号：在 classpath 按资源名查找 lingframe-example-ling-test.jar
        // jar 放在 src/test/resources/，由 Maven 复制到 target/test-classes/
        File file = findLingTestJar();
        try {
            // 订阅 LeakDetectionEvent
            CountDownLatch leakLatch = new CountDownLatch(1);
            AtomicReference<MonitoringEvents.LeakDetectionEvent> leakEvent = new AtomicReference<>();
            eventBus.subscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, e -> {
                if (LING_ID.equals(e.getLingId())) {
                    leakEvent.set(e);
                    leakLatch.countDown();
                }
            });

            LingDefinition definition = LingManifestLoader.parseDefinition(file);
            if (definition == null) {
                throw new InvalidArgumentException("file", "Not a valid ling package: " + file.getName());
            }
            lifecycleEngine.deploy(definition, file, false, Collections.emptyMap());

            LingUninstallResult result = lifecycleEngine.undeployWithReport(definition.getId());
            log.info("undeploy result:{}", result.isUninstallTriggered());

            // 等待 LeakDetectionEvent
            boolean received = leakLatch.await(30, TimeUnit.SECONDS);
            assertTrue(received, "应在超时前收到 LeakDetectionEvent");

            MonitoringEvents.LeakDetectionEvent event = leakEvent.get();
            assertNotNull(event, "LeakDetectionEvent 不应为 null");

            if (!event.isCollected()) {
                // heap dump + 可疑线程 TCCL + 灵核 Bean 字段扫描（统一诊断类）
                ClassLoaderLeakDiagnoser.diagnoseAfterGcFailure(
                        SpringLingContainerUnloadRegressionTest.class.getName(), coreApplicationContext);
            }
            assertTrue(event.isCollected(), "ClassLoader 应在开发部署-卸载链路后被 GC 回收");
        } catch (Exception e) {
            throw new LingInstallException("unknown", "Failed to install ling: " + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("通过生产部署-卸载链路应回收 ClassLoader（含 Web 路由注册/分发/注销）")
    void shouldReleaseClassLoaderThroughProductionDeployUndeployPath() throws Exception {
        // 订阅 LeakDetectionEvent
        CountDownLatch leakLatch = new CountDownLatch(1);
        AtomicReference<MonitoringEvents.LeakDetectionEvent> leakEvent = new AtomicReference<>();
        eventBus.subscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, e -> {
            if (LING_ID.equals(e.getLingId())) {
                leakEvent.set(e);
                leakLatch.countDown();
            }
        });

        Path workspace = Files.createTempDirectory("spring-ling-unload");
        Path classesDir = workspace.resolve("classes");
        copyLingAppClass(classesDir);
        // 写 ling.yml，与 Dashboard installLing 走 LingManifestLoader.parseDefinition 一致
        String lingYml = "id: " + LING_ID + "\n"
                + "version: " + LING_VERSION + "\n"
                + "mainClass: \"" + APP_CLASS_NAME + "\"\n";
        Files.write(classesDir.resolve("ling.yml"), lingYml.getBytes(StandardCharsets.UTF_8));

        // 默认 prefixWithLingId=false：灵元 Controller 保持原生路径 /demo/ping（无感接入/替换）
        String routePath = "/demo/ping";
        try {
            // ========== 生产部署路径（一比一照搬 Dashboard.installLing） ==========
            // Dashboard: parseDefinition(file) -> lifecycleEngine.deploy(definition, file,
            // !isCanary, emptyMap())
            LingDefinition definition = LingManifestLoader.parseDefinition(classesDir.toFile());
            assertNotNull(definition, "ling.yml 解析应成功");
            lifecycleEngine.deploy(definition, classesDir.toFile(), true, Collections.emptyMap());

            // ========== 验证 Web 路由已注册 + 请求接口 ==========
            // 用独立作用域包裹 WebRouteResolution 等强引用，块结束后释放，避免阻止后续 GC 验证
            {
                MockHttpServletRequest routeRequest = new MockHttpServletRequest("GET", routePath);
                WebRouteResolution resolution = webInterfaceManager.resolveRoute(routeRequest);
                assertNotNull(resolution, "Web 路由应在 deploy 后注册");
                assertNotNull(resolution.getMetadata().getClassLoader(),
                        "ClassLoader 在 undeploy 前不应被回收");

                // ========== 请求接口（完整请求流程）==========
                // deploy 后实际调用 /demo/ping，验证灵元 Controller 能正常处理请求
                // ServletWebRequest 须按 javax/jakarta 反射构造（SB2/SB3 双栈），见 createServletWebRequest
                MockHttpServletResponse pingResponse = new MockHttpServletResponse();
                ServletWebRequest pingWebRequest = createServletWebRequest(routeRequest, pingResponse);
                webInterfaceManager.dispatch(resolution.getRouteKey(), pingWebRequest);
                assertEquals("pong", pingResponse.getContentAsString(),
                        "ping 接口应返回 pong");

                // 不在此调用 coreMapping.getHandler / RequestMappingHandlerMapping.getHandler：
                // SB3 要求 ServletRequestPathUtils 预解析路径，getHandler 会把 pathLookup / RequestPath
                // 写入 request attributes 与 mapping 内部结构；与 CL 回收断言耦合时易假阴性。
                // 路由可达性已由 resolveRoute + dispatch 覆盖；mapping 注销由 unregister + cleaner 负责。
                // 若需 mapping 级断言，应放在独立用例中，且不要与「卸载后 CL 可回收」硬断言绑在同一窗口。

                // 清理 request attributes：resolveRoute / dispatch 会将 WebInterfaceMetadata
                // 存入 request attributes，其 classLoader 字段强引用灵元 CL。
                // MockHttpServletRequest 作为局部变量存活到方法结束，需显式清理避免阻止 GC
                // （生产环境由 DispatcherServlet 在请求结束时自动清理，测试需手动模拟）
                routeRequest.clearAttributes();
            }
            // resolution / pingResponse / pingWebRequest 在块结束时出栈释放

            // ========== 生产卸载路径（一比一照搬 Dashboard.uninstallLing） ==========
            // Dashboard: canaryRouter.removeCanaryConfig(lingId) ->
            // lifecycleEngine.undeployWithReport(lingId)
            LingUninstallResult result = lifecycleEngine.undeployWithReport(definition.getId());
            log.info("undeploy result:{}", result.isUninstallTriggered());

            // ========== 验证 Web 路由已注销 ==========
            MockHttpServletRequest afterRequest = new MockHttpServletRequest("GET", routePath);
            assertNull(webInterfaceManager.resolveRoute(afterRequest),
                    "Web 路由应在 undeploy 后注销");
            // 注销后只断言 WebInterfaceManager 路由表（见上方为何不做 getHandler 的说明）。
            // SB3：若将来恢复 getHandler 断言，必须在断言后 clearAttributes——
            // getHandler 会调用 RequestMappingHandlerMapping.getHandler，内部 ServletRequestPathUtils
            // 把解析结果写入 afterRequest attributes；MockHttpServletRequest 在方法栈中存活到 await 结束，
            // 显式清理 attributes 避免任何残留引用阻止 GC（生产环境由 DispatcherServlet 自动清理）。
            afterRequest.clearAttributes();

            // 等待 LeakDetectionEvent
            boolean received = leakLatch.await(30, TimeUnit.SECONDS);
            assertTrue(received, "应在超时前收到 LeakDetectionEvent");

            MonitoringEvents.LeakDetectionEvent event = leakEvent.get();
            assertNotNull(event, "LeakDetectionEvent 不应为 null");

            if (!event.isCollected()) {
                // 诊断 SB3 残留持有链：heap dump + 活动线程 TCCL + 灵核单例 Bean 字段扫描
                // （实现见 ClassLoaderLeakDiagnoser，勿再把诊断逻辑散回本类）
                ClassLoaderLeakDiagnoser.diagnoseAfterGcFailure(
                        SpringLingContainerUnloadRegressionTest.class.getName(), coreApplicationContext);
            }
            // MethodClassKey / ConcurrentReferenceHashMap Soft 路径（BridgeMethodResolver.cache 等）
            // 已在 SpringStaticCacheCleaner 按 dump 证据清理。若仍失败，DIAG + heap dump 继续迭代。
            assertTrue(event.isCollected(), "ClassLoader 应在生产卸载链路（含 Web dispatch）后被 GC 回收");
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 基础设施方法
    // =========================================================================

    /**
     * 双栈构造 {@link ServletWebRequest}：按 Mock 请求 ClassLoader 选择 jakarta/javax 接口签名。
     * <p>
     * SB3 的 ServletWebRequest 构造签名是 jakarta.servlet.http.*，SB2 是 javax.servlet.http.*；
     * 直接 {@code new ServletWebRequest(mockReq, mockResp)} 在 SB3 会 NoSuchMethodError。
     */
    private ServletWebRequest createServletWebRequest(Object request, Object response) throws Exception {
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> requestIntf = findServletInterface(cl, "HttpServletRequest");
        Class<?> responseIntf = findServletInterface(cl, "HttpServletResponse");
        if (requestIntf == null || responseIntf == null) {
            throw new IllegalStateException("Cannot resolve Servlet request/response interfaces for "
                    + request.getClass().getName());
        }
        return (ServletWebRequest) ReflectionUtils
                .accessibleConstructor(ServletWebRequest.class, requestIntf, responseIntf)
                .newInstance(request, response);
    }

    private Class<?> findServletInterface(ClassLoader cl, String interfaceName) {
        try {
            return Class.forName("jakarta.servlet.http." + interfaceName, false, cl);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName("javax.servlet.http." + interfaceName, false, cl);
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
    }

    private void copyLingAppClass(Path classesDir) throws IOException {
        Path packageDir = classesDir.resolve("sample/springling");
        Files.createDirectories(packageDir);

        ClassLoader classLoader = getClass().getClassLoader();
        URL mainClassUrl = classLoader.getResource("sample/springling/SampleLingApp.class");
        if (mainClassUrl == null) {
            throw new IllegalStateException("SampleLingApp.class not found in test classpath");
        }
        try (InputStream is = mainClassUrl.openStream()) {
            Files.copy(is, packageDir.resolve("SampleLingApp.class"));
        }

        URL controllerClassUrl = classLoader.getResource("sample/springling/SampleLingApp$DemoController.class");
        if (controllerClassUrl != null) {
            try (InputStream is = controllerClassUrl.openStream()) {
                Files.copy(is, packageDir.resolve("SampleLingApp$DemoController.class"));
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    /**
     * 从 classpath 加载 lingframe-example-ling-test.jar。
     * <p>
     * 不硬编码绝对路径和版本号：jar 文件放在 {@code src/test/resources/} 下，
     * 由 Maven 在 test-compile 阶段复制到 {@code target/test-classes/}，运行时通过 classpath 定位。
     *
     * @return classpath 资源对应的文件对象
     * @throws IllegalStateException 资源未找到或 URL 转 File 失败时抛出，错误信息含操作指引
     */
    private static File findLingTestJar() {
        URL url = SpringLingContainerUnloadRegressionTest.class.getClassLoader()
                .getResource("lingframe-example-ling-test.jar");
        if (url == null) {
            throw new IllegalStateException(
                    "classpath 下未找到 lingframe-example-ling-test.jar，请将 jar 放到 src/test/resources/ 下");
        }
        try {
            return new File(url.toURI());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法将 classpath 资源 lingframe-example-ling-test.jar 转为 File: " + url, e);
        }
    }
}
