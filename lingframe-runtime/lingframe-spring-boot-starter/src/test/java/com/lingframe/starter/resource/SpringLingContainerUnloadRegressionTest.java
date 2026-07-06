package com.lingframe.starter.resource;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.LingClassLoader;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.starter.configuration.LingFrameCoreConfiguration;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebRouteResolution;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * Spring 灵元容器卸载回归测试。
 * <p>
 * 完全走生产的真实路径：
 * 1. 通过 {@code LingManifestLoader.parseDefinition()} + {@code lifecycleEngine.deploy()} 部署灵元
 * 2. 通过 {@code lifecycleEngine.undeployWithReport()} 卸载灵元
 * 3. 验证 ClassLoader 能被 GC 回收
 */
@Slf4j
@SpringBootTest(classes = LingTestSpringConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
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

    @Test
    @DisplayName("通过开发部署-卸载链路应回收 ClassLoader")
    void shouldReleaseClassLoaderThroughDevelopmentDeployUndeployPath() throws Exception {
        File file = new File("E:\\Codes\\灵珑\\LingFrame\\lings\\lingframe-example-ling-test-0.3.0.jar");
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

    @Test
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
            // Dashboard: parseDefinition(file) -> lifecycleEngine.deploy(definition, file, !isCanary, emptyMap())
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

                // 验证宿主 HandlerMapping 也能路由到灵元 Controller
                HandlerExecutionChain chain = getHandlerExecutionChain(hostMapping, routeRequest);
                assertNotNull(chain, "宿主 HandlerMapping 应能路由到灵元 Controller");

                // 清理 request attributes：resolveRoute / dispatch 会将 WebInterfaceMetadata
                // 存入 request attributes，其 classLoader 字段强引用灵元 CL。
                // MockHttpServletRequest 作为局部变量存活到方法结束，需显式清理避免阻止 GC
                // （生产环境由 DispatcherServlet 在请求结束时自动清理，测试需手动模拟）
                routeRequest.clearAttributes();
            }
            // resolution / pingResponse / pingWebRequest / chain 在块结束时出栈释放

            // ========== 生产卸载路径（一比一照搬 Dashboard.uninstallLing） ==========
            // Dashboard: canaryRouter.removeCanaryConfig(lingId) -> lifecycleEngine.undeployWithReport(lingId)
            LingUninstallResult result = lifecycleEngine.undeployWithReport(definition.getId());
            log.info("undeploy result:{}", result.isUninstallTriggered());

            // ========== 验证 Web 路由已注销 ==========
            MockHttpServletRequest afterRequest = new MockHttpServletRequest("GET", routePath);
            assertNull(webInterfaceManager.resolveRoute(afterRequest),
                    "Web 路由应在 undeploy 后注销");
            assertNull(getHandlerExecutionChain(hostMapping, afterRequest),
                    "宿主 HandlerMapping 不应再路由到灵元 Controller");

            // 等待 LeakDetectionEvent
            boolean received = leakLatch.await(30, TimeUnit.SECONDS);
            assertTrue(received, "应在超时前收到 LeakDetectionEvent");

            MonitoringEvents.LeakDetectionEvent event = leakEvent.get();
            assertNotNull(event, "LeakDetectionEvent 不应为 null");

            if (!event.isCollected()) {
                ClassLoaderLeakDiagnoser.dumpHeap(SpringLingContainerUnloadRegressionTest.class.getName(), true);
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
}
