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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private RequestMappingHandlerMapping hostMapping;

    @Autowired
    private org.springframework.context.ApplicationContext hostApplicationContext;

    @Test
    @DisplayName("通过开发部署-卸载链路应回收 ClassLoader")
    void shouldReleaseClassLoaderThroughDevelopmentDeployUndeployPath() throws Exception {
        // 不再硬编码绝对路径和版本号：在 lings/ 目录下按通配符查找 lingframe-example-ling-test-*.jar
        // 测试运行前请将构建好的 jar 放到项目根目录的 lings/ 下
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
                ClassLoaderLeakDiagnoser.dumpHeap(SpringLingContainerUnloadRegressionTest.class.getName(), true);
            }
            assertTrue(event.isCollected(), "ClassLoader 应在生产卸载链路后被 GC 回收");
        } catch (Exception e) {
            throw new LingInstallException("unknown", "Failed to install ling: " + e.getMessage(), e);
        }
    }

    // @Test
    @DisplayName("通过生产部署-卸载链路应回收 ClassLoader")
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

        String routePath = "/" + LING_ID + "/demo/ping";
        try {
            // ========== 生产部署路径（一比一照搬 Dashboard.installLing） ==========
            // Dashboard: parseDefinition(file) -> lifecycleEngine.deploy(definition, file,
            // !isCanary, emptyMap())
            LingDefinition definition = LingManifestLoader.parseDefinition(classesDir.toFile());
            assertNotNull(definition, "ling.yml 解析应成功");
            lifecycleEngine.deploy(definition, classesDir.toFile(), true, Collections.emptyMap());

            // ========== 验证 Web 路由已注册 + 请求接口 ==========
            // 用独立作用域包裹 WebRouteResolution 等强引用，块结束后释放，避免阻止后续 GC 验证
            MockHttpServletRequest routeRequest = new MockHttpServletRequest("GET", routePath);
            {
                WebRouteResolution resolution = webInterfaceManager.resolveRoute(routeRequest);
                assertNotNull(resolution, "Web 路由应在 deploy 后注册");
                assertNotNull(resolution.getMetadata().getClassLoader(),
                        "ClassLoader 在 undeploy 前不应被回收");

                // ========== 请求接口（完整请求流程）==========
                // deploy 后实际调用 /test-ling/demo/ping，验证灵元 Controller 能正常处理请求
                MockHttpServletResponse pingResponse = new MockHttpServletResponse();
                ServletWebRequest pingWebRequest = new ServletWebRequest(routeRequest, pingResponse);
                webInterfaceManager.dispatch(resolution.getRouteKey(), pingWebRequest);
                assertEquals("pong", pingResponse.getContentAsString(),
                        "ping 接口应返回 pong");

                // 验证灵核 HandlerMapping 也能路由到灵元 Controller
                HandlerExecutionChain chain = getHandlerExecutionChain(hostMapping, routeRequest);
                assertNotNull(chain, "灵核 HandlerMapping 应能路由到灵元 Controller");

                // 清理 request attributes：resolveRoute / dispatch 会将 WebInterfaceMetadata
                // 存入 request attributes，其 classLoader 字段强引用灵元 CL。
                // MockHttpServletRequest 作为局部变量存活到方法结束，需显式清理避免阻止 GC
                // （生产环境由 DispatcherServlet 在请求结束时自动清理，测试需手动模拟）
                routeRequest.clearAttributes();
            }
            // resolution / pingResponse / pingWebRequest / chain 在块结束时出栈释放

            // ========== 生产卸载路径（一比一照搬 Dashboard.uninstallLing） ==========
            // Dashboard: canaryRouter.removeCanaryConfig(lingId) ->
            // lifecycleEngine.undeployWithReport(lingId)
            LingUninstallResult result = lifecycleEngine.undeployWithReport(definition.getId());
            log.info("undeploy result:{}", result.isUninstallTriggered());

            // ========== 验证 Web 路由已注销 ==========
            MockHttpServletRequest afterRequest = new MockHttpServletRequest("GET", routePath);
            assertNull(webInterfaceManager.resolveRoute(afterRequest),
                    "Web 路由应在 undeploy 后注销");
            assertNull(getHandlerExecutionChain(hostMapping, afterRequest),
                    "灵核 HandlerMapping 不应再路由到灵元 Controller");
            // SB3 兼容：getHandlerExecutionChain 会调用 RequestMappingHandlerMapping.getHandler，
            // 内部 ServletRequestPathUtils 把解析结果写入 afterRequest attributes；
            // 该 attribute 不直接引用灵元 Class，但 MockHttpServletRequest 在方法栈中存活到 await 结束，
            // 显式清理 attributes 避免任何残留引用阻止 GC（生产环境由 DispatcherServlet 自动清理）。
            afterRequest.clearAttributes();

            // 等待 LeakDetectionEvent
            boolean received = leakLatch.await(30, TimeUnit.SECONDS);
            assertTrue(received, "应在超时前收到 LeakDetectionEvent");

            MonitoringEvents.LeakDetectionEvent event = leakEvent.get();
            assertNotNull(event, "LeakDetectionEvent 不应为 null");

            if (!event.isCollected()) {
                ClassLoaderLeakDiagnoser.dumpHeap(SpringLingContainerUnloadRegressionTest.class.getName(), true);
                // 诊断 SB3 残留持有链：扫所有活动线程的 contextClassLoader，找出仍持灵元 CL 的线程
                diagnoseSuspectThreadsAfterGc();
                // 诊断 SB3 残留持有链：扫灵核所有单例 Bean 的字段，找出仍持灵元 Class/ClassLoader 的 Bean
                diagnoseSuspectBeansAfterGc();
            }
            assertTrue(event.isCollected(), "ClassLoader 应在生产卸载链路后被 GC 回收");
        } finally {
            deleteRecursively(workspace);
        }
    }

    // =========================================================================
    // 基础设施方法
    // =========================================================================

    private HandlerExecutionChain getHandlerExecutionChain(RequestMappingHandlerMapping mapping,
            Object request) throws Exception {
        // Spring Boot 3 要求请求路径已被 ServletRequestPathUtils 预解析，
        // 否则 RequestMappingHandlerMapping.getHandler 抛 IllegalArgument：
        // "Expected parsed RequestPath in request attribute ...PATH"。
        // Spring Boot 2 没有这一前置要求，parseAndCache 也不存在，
        // 因此在 SB3 反射调用 parseAndCache，在 SB2 直接跳过。
        parseRequestPathIfSpringBoot3(request);
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> requestIntf = findServletInterface(cl, "HttpServletRequest");
        Method getHandlerMethod = org.springframework.util.ReflectionUtils
                .findMethod(mapping.getClass(), "getHandler", requestIntf);
        if (getHandlerMethod == null) {
            return null;
        }
        return (HandlerExecutionChain) org.springframework.util.ReflectionUtils
                .invokeMethod(getHandlerMethod, mapping, request);
    }

    /**
     * Spring Boot 3 兼容：调用 {@code ServletRequestPathUtils.parseAndCache} 预解析请求路径。
     * <p>
     * SB3 的 {@code RequestMappingHandlerMapping.getHandler} 内部通过
     * {@code ServletRequestPathUtils.getParsedRequestPath} 取出已解析的 RequestPath，
     * 若 request 未预解析则抛 {@code IllegalArgumentException}。
     * {@code MockHttpServletRequest} 不会自动触发预解析，需要测试显式调用。
     * <p>
     * Spring Boot 2 没有该类，直接跳过。
     */
    private static void parseRequestPathIfSpringBoot3(Object request) {
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> util;
        try {
            util = Class.forName("org.springframework.web.util.ServletRequestPathUtils", false, cl);
        } catch (ClassNotFoundException ignored) {
            // Spring Boot 2 没有 ServletRequestPathUtils，跳过
            return;
        }
        // parseAndCache 签名：parseAndCache(HttpServletRequest)
        // 用接口类型查找方法，避免 MockHttpServletRequest 具体类型不匹配
        Class<?> requestIntf = null;
        try {
            requestIntf = Class.forName("jakarta.servlet.http.HttpServletRequest", false, cl);
        } catch (ClassNotFoundException ignored) {
            // 非 SB3 环境
        }
        Method parseAndCache = null;
        if (requestIntf != null) {
            parseAndCache = org.springframework.util.ReflectionUtils
                    .findMethod(util, "parseAndCache", requestIntf);
        }
        if (parseAndCache == null) {
            return;
        }
        try {
            parseAndCache.invoke(null, request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke ServletRequestPathUtils.parseAndCache", e);
        }
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

    /**
     * 诊断 SB3 残留持有链：扫所有活动线程的 contextClassLoader，
     * 找出仍持有灵元 ClassLoader（或其内部类）的线程，输出线程名/状态/daemon/contextCL。
     * 仅在 GC 失败时调用，用于定位残留阻止点。
     */
    private void diagnoseSuspectThreadsAfterGc() {
        try {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) {
                root = root.getParent();
            }
            Thread[] threads = new Thread[root.activeCount() * 2 + 50];
            root.enumerate(threads, true);
            int suspect = 0;
            for (Thread t : threads) {
                if (t == null)
                    continue;
                ClassLoader tccl = t.getContextClassLoader();
                if (tccl == null)
                    continue;
                String tcclName = tccl.getClass().getName();
                if (tcclName.contains("LingClassLoader")) {
                    log.warn("[DIAG-Thread] suspect thread: name='{}', state={}, daemon={}, alive={}, contextCL={}@{}",
                            t.getName(), t.getState(), t.isDaemon(), t.isAlive(),
                            tcclName, Integer.toHexString(System.identityHashCode(tccl)));
                    suspect++;
                }
            }
            log.warn("[DIAG-Thread] total suspect threads holding LingClassLoader: {}", suspect);
        } catch (Exception e) {
            log.warn("[DIAG-Thread] scan failed: {}", e.getMessage());
        }
    }

    /**
     * 诊断 SB3 残留持有链：扫灵核 ApplicationContext 所有单例 Bean 的非静态字段，
     * 找出仍持有灵元 ClassLoader / 灵元 Class 的 Bean，输出 Bean 名/类型/字段路径。
     * 仅在 GC 失败时调用，用于定位残留对象强引用。
     * <p>
     * 限制扫描深度为 2 层（Bean → 字段 → 字段值），避免递归过深；
     * 跳过 JDK / Spring 标准类（按 package name 判定），只扫灵核或可疑第三方类。
     */
    private void diagnoseSuspectBeansAfterGc() {
        org.springframework.context.ApplicationContext ctx = hostApplicationContext;
        if (ctx == null) {
            // 当前不是 Spring Test 上下文，跳过
            return;
        }
        try {
            String[] names = ctx.getBeanDefinitionNames();
            int suspect = 0;
            for (String name : names) {
                Object bean;
                try {
                    bean = ctx.getBean(name);
                } catch (Exception ignored) {
                    continue;
                }
                if (bean == null)
                    continue;
                String beanTypeName = bean.getClass().getName();
                // 跳过灵核基础设施标准类，只扫可疑类
                if (beanTypeName.startsWith("java.")
                        || beanTypeName.startsWith("org.springframework.")
                        || beanTypeName.startsWith("jakarta.")
                        || beanTypeName.startsWith("javax.")) {
                    continue;
                }
                if (scanFieldsForClassLoader(bean, bean.getClass(), 0, 2, name)) {
                    suspect++;
                }
            }
            log.warn("[DIAG-Bean] total suspect beans holding LingClassLoader: {}", suspect);
        } catch (Exception e) {
            log.warn("[DIAG-Bean] scan failed: {}", e.getMessage());
        }
    }

    /**
     * 反射扫对象字段（限定深度）找出持有灵元 ClassLoader 或灵元 Class 的引用。
     * 返回 true 表示找到至少一处可疑引用。
     */
    private boolean scanFieldsForClassLoader(Object root, Class<?> type, int depth, int maxDepth, String path) {
        if (root == null || depth > maxDepth)
            return false;
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                continue;
            try {
                f.setAccessible(true);
                Object value = f.get(root);
                if (value == null)
                    continue;
                ClassLoader vCl = value.getClass().getClassLoader();
                if (vCl != null && vCl.getClass().getName().contains("LingClassLoader")) {
                    log.warn("[DIAG-Bean] suspect field: {}.{} -> value type={} loaded by LingClassLoader@{}",
                            path, f.getName(), value.getClass().getName(),
                            Integer.toHexString(System.identityHashCode(vCl)));
                    return true;
                }
                if (value instanceof Class<?>) {
                    ClassLoader cCl = ((Class<?>) value).getClassLoader();
                    if (cCl != null && cCl.getClass().getName().contains("LingClassLoader")) {
                        log.warn("[DIAG-Bean] suspect Class field: {}.{} -> {} loaded by LingClassLoader@{}",
                                path, f.getName(), ((Class<?>) value).getName(),
                                Integer.toHexString(System.identityHashCode(cCl)));
                        return true;
                    }
                }
                if (depth < maxDepth && !value.getClass().getName().startsWith("java.")
                        && !value.getClass().getName().startsWith("org.springframework.")) {
                    if (scanFieldsForClassLoader(value, value.getClass(), depth + 1, maxDepth,
                            path + "." + f.getName())) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 跳过不可达字段
            }
        }
        return false;
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
