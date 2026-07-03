package com.lingframe.starter.resource;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.LingClassLoader;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.DefaultLingLifecycleEngine;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LifecycleEngineConfig;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.LingUnloadCoordinator;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.resource.DefaultLeakDetector;
import com.lingframe.core.resource.JdbcDriverUnloadHook;
import com.lingframe.core.resource.JvmShutdownHookUnloadHook;
import com.lingframe.core.resource.ThreadReferenceUnloadHook;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.web.WebInterfaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.context.support.GenericApplicationContext;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spring 灵元容器卸载回归测试。
 * <p>
 * 完全走生产标准路径：通过 {@link DefaultLingLifecycleEngine#deploy} 安装灵元，
 * 通过 {@link DefaultLingLifecycleEngine#undeploy} 卸载灵元，
 * 装配链路对齐 {@code LingFrameLifecycleBeansConfiguration}（生态桶 2 hook + JVM 桶 3 hook = 5 hook），
 * 泄漏判定对齐 {@code DefaultLeakDetector} + {@code LingUnloadCollectabilityRegressionTest}。
 * <p>
 * 不再手动 new SpringLingContainer + stop + cleanup，避免绕过 InstancePool / onVersionUnload / detectLeak。
 */
@DisplayName("SpringLingContainer 卸载回归测试")
class SpringLingContainerUnloadRegressionTest {

    private static final String APP_CLASS_NAME = "sample.springling.SampleLingApp";

    private DefaultLingLifecycleEngine lifecycleEngine;
    private EventBus eventBus;
    private RuntimeCoordinator runtimeCoordinator;
    private LeakDetector leakDetector;
    private AtomicReference<LingClassLoader> loaderHolder;

    @AfterEach
    void tearDown() throws Exception {
        if (runtimeCoordinator != null) {
            runtimeCoordinator.stop();
        }
        if (leakDetector != null) {
            leakDetector.shutdown();
        }
        if (eventBus != null) {
            eventBus.shutdown();
        }
        // 重置 Spring Boot 静态 shutdownHook，防止跨测试残留
        try {
            Object bootShutdownHook = bootShutdownHook();
            Method resetMethod = bootShutdownHook.getClass().getDeclaredMethod("reset");
            resetMethod.setAccessible(true);
            resetMethod.invoke(bootShutdownHook);
        } catch (Throwable ignored) {
        }
    }

    @Test
    @DisplayName("通过生产卸载链路应释放 Spring 灵核侧引用并回收 ClassLoader")
    void shouldReleaseSpringHostSideReferencesThroughProductionUndeployPath() throws Exception {
        try (TestHost host = TestHost.create()) {
            CycleResult cycle = runCycle(host, "order-ling");

            assertTrue(cycle.classLoaderClosed, "ClassLoader 应被关闭");
            assertFalse(cycle.runtimeStillRegistered, "运行时应从 Repository 注销");
            assertTrue(cycle.routeRemoved, "Web 路由应被注销");
            assertTrue(cycle.noThreadContextClassLoaderLeak, "线程 contextClassLoader 不应残留");
            assertClassLoaderCollected(cycle.classLoaderCollected, cycle.leakedClassLoader);
        }
    }

    @Test
    @DisplayName("重复生产卸载链路后仍应可被回收")
    void shouldRemainCollectibleAcrossRepeatedProductionUndeployCycles() throws Exception {
        try (TestHost host = TestHost.create()) {
            for (int i = 0; i < 3; i++) {
                CycleResult cycle = runCycle(host, "order-ling-" + i);

                assertTrue(cycle.classLoaderClosed);
                assertFalse(cycle.runtimeStillRegistered);
                assertTrue(cycle.routeRemoved);
                assertTrue(cycle.noThreadContextClassLoaderLeak);
                assertClassLoaderCollected(cycle.classLoaderCollected, cycle.leakedClassLoader);
            }
        }
    }

    /**
     * 装配生产标准链路（对齐 LingFrameLifecycleBeansConfiguration）并执行一次 deploy→undeploy 周期。
     */
    private CycleResult runCycle(TestHost host, String lingId) throws Exception {
        Path workspace = Files.createTempDirectory("spring-ling-unload");
        Path sourceDir = workspace.resolve("src");
        Path classesDir = workspace.resolve("classes");
        compileLingApp(sourceDir, classesDir);

        try {
            return runCycleInternal(host, lingId, classesDir);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private CycleResult runCycleInternal(TestHost host, String lingId, Path classesDir) throws Exception {
        // ==================== 装配生产标准链路（对齐 LingFrameLifecycleBeansConfiguration） ====================
        eventBus = new EventBus();
        runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingRepository repository = new DefaultLingRepository();
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed(isNull(), eq("test:invoke"), isNull())).thenReturn(true);

        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        when(serviceRegistry.getServicesByLingId(lingId)).thenReturn(Collections.emptyList());

        FilterRegistry registry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(new InvokableMethodCache())
                .permissionService(permissionService)
                .lingRepository(repository)
                .trafficRouter(new LatestVersionPolicy())
                .eventBus(eventBus)
                .runtimeCoordinator(runtimeCoordinator)
                .build());
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(registry);

        // 生态桶：Spring 生态清理 Hook（对齐 LingFrameRuntimeBeansConfiguration 注册的 2 个 @Bean）
        List<LingUnloadHook> ecosystemHooks = Arrays.asList(
                new SpringEcosystemUnloadHook(),
                new StorageCacheUnloadHook());
        // JVM 桶：三个独立 JVM 级 Hook（对齐 LingFrameLifecycleBeansConfiguration L109-112 硬编码）
        List<LingUnloadHook> jvmHooks = Arrays.asList(
                new JdbcDriverUnloadHook(),
                new ThreadReferenceUnloadHook(),
                new JvmShutdownHookUnloadHook());

        LingFrameConfig lingFrameConfig = LingFrameConfig.builder()
                .apiOverrideCheckEnabled(false)
                .devMode(true)
                .leakDetectionDevStartDelayMillis(100)
                .leakDetectionAggressiveGcRounds(20)
                .leakDetectionAggressiveGcIntervalMillis(200)
                .leakDetectionFinalConfirmationDelayMillis(2000)
                .runtimeConfig(LingRuntimeConfig.builder()
                        .bulkheadMaxConcurrent(1)
                        .defaultTimeoutMs(1000)
                        .rateLimitPerSecond(5)
                        .forceCleanupDelaySeconds(0)
                        .build())
                .build();

        // 泄漏检测器：生产 DefaultLeakDetector，devMode=true 走主动 GC 轮询
        leakDetector = new DefaultLeakDetector(eventBus, lingFrameConfig);

        // 订阅 LeakDetectionEvent，用 DefaultLeakDetector 的判定结果做断言（不再自造 WeakReference.get() 轮询）
        CountDownLatch leakLatch = new CountDownLatch(1);
        AtomicReference<MonitoringEvents.LeakDetectionEvent> leakEvent = new AtomicReference<>();
        eventBus.subscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, e -> {
            if (lingId.equals(e.getLingId())) {
                leakEvent.set(e);
                leakLatch.countDown();
            }
        });

        LingUnloadCoordinator unloadCoordinator = new LingUnloadCoordinator(
                pipelineEngine, ecosystemHooks, jvmHooks, null, leakDetector);

        // LingFrameProperties bean：SpringContainerFactory 构造时需要
        // GenericApplicationContext 不支持二次 refresh，registerBean 在 refresh 后直接可用
        // devMode=true 让 SpringContainerFactory.create 异常透传（非 devMode 下异常被吞返回 null）
        LingFrameProperties props = new LingFrameProperties();
        props.setDevMode(true);
        host.hostContext.getBeanFactory().registerSingleton(
                "lingFrameProperties", props);

        // ContainerFactory：真实 SpringContainerFactory（对齐 LingFrameLifecycleBeansConfiguration L76）
        ContainerFactory containerFactory = new SpringContainerFactory(
                host.hostContext, host.manager, Collections.emptyList(), ecosystemHooks);

        // LingLoaderFactory：造 LingClassLoader（生产用 LingClassLoader，非 URLClassLoader）
        AtomicBoolean classLoaderClosed = new AtomicBoolean(false);
        loaderHolder = new AtomicReference<>();
        LingLoaderFactory loaderFactory = (id, sourceFile, parent) -> {
            classLoaderClosed.set(false);
            URL[] urls;
            try {
                urls = new URL[] { classesDir.toUri().toURL() };
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException("Failed to resolve classesDir URL", e);
            }
            CloseAwareLingClassLoader cl = new CloseAwareLingClassLoader(
                    id,
                    urls,
                    parent,
                    classLoaderClosed);
            loaderHolder.set(cl);
            return cl;
        };

        List<LingSecurityVerifier> verifiers = Collections.singletonList(new DangerousApiVerifier(false));

        lifecycleEngine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(permissionService)
                .lingLoaderFactory(loaderFactory)
                .verifiers(verifiers)
                .eventBus(eventBus)
                .lingFrameConfig(lingFrameConfig)
                .lingRepository(repository)
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(pipelineEngine)
                .lingResourceManager(null)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .leakDetector(leakDetector)
                .build());

        // ==================== 生产路径：deploy ====================
        LingDefinition definition = new LingDefinition();
        definition.setId(lingId);
        definition.setVersion("1.0.0");
        definition.setMainClass(APP_CLASS_NAME);

        lifecycleEngine.deploy(definition, classesDir.toFile(), true, Collections.emptyMap());

        CloseAwareLingClassLoader lingClassLoader = (CloseAwareLingClassLoader) loaderHolder.get();
        assertNotNull(lingClassLoader, "ClassLoader 应在 deploy 后被创建");
        WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(lingClassLoader);

        // ==================== Web 请求模拟（B 场景核心：验证 Spring 灵元 Controller 注册与路由） ====================
        String routePath = "/" + lingId + "/demo/ping";
        assertNotNull(host.manager.resolveRoute(new MockHttpServletRequest("GET", routePath)),
                "Web 路由应在 deploy 后注册");
        assertEquals("pong", performWebRequest(host, routePath), "Web 请求应成功响应");

        // ==================== 生产路径：undeploy（真路径，走 InstancePool.removeInstance + onVersionUnload + detectLeak） ====================
        lifecycleEngine.undeploy(lingId);

        boolean runtimeStillRegistered = repository.hasRuntime(lingId);
        boolean classLoaderClosedFlag = classLoaderClosed.get();
        boolean routeRemoved = host.manager.resolveRoute(new MockHttpServletRequest("GET", routePath)) == null;
        assertNull(getHandlerExecutionChain(host.hostMapping, new MockHttpServletRequest("GET", routePath)),
                "宿主 HandlerMapping 不应再路由到灵元 Controller");

        // ==================== 释放测试侧强引用，等待 DefaultLeakDetector 判定 ====================
        lingClassLoader = null;
        loaderHolder.set(null);
        lifecycleEngine = null;

        // 等待 DefaultLeakDetector 异步发 LeakDetectionEvent（devMode 走 aggressive GC 轮询 + 最终确认窗口）
        // 总超时 = devStartDelay + aggressiveGcRounds*interval + finalConfirmationDelay，留足余量
        boolean received = leakLatch.await(30, TimeUnit.SECONDS);
        assertTrue(received, "应在超时前收到 LeakDetectionEvent");

        MonitoringEvents.LeakDetectionEvent event = leakEvent.get();
        assertNotNull(event, "LeakDetectionEvent 不应为 null");
        boolean classLoaderCollected = event.isCollected();
        boolean noThreadContextClassLoaderLeak = findThreadContextClassLoaderReference(classLoaderRef.get()) == null;

        // 验证生产链路的副作用
        verify(serviceRegistry).evict(lingId);
        verify(permissionService).removeLing(lingId);

        return new CycleResult(
                classLoaderClosedFlag,
                runtimeStillRegistered,
                routeRemoved,
                noThreadContextClassLoaderLeak,
                classLoaderCollected,
                classLoaderRef.get());
    }

    /**
     * 编译带 Spring Controller 的灵元源码。
     * 不再依赖 SpringLingContainerUnloadRegressionSupport（生产路径由 Ling.onStart/onStop + SpringEcosystemUnloadHook 管生命周期）。
     */
    private void compileLingApp(Path sourceDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is unavailable");
        }

        Path packageDir = sourceDir.resolve("sample/springling");
        Files.createDirectories(packageDir);
        Files.createDirectories(classesDir);

        Path sourceFile = packageDir.resolve("SampleLingApp.java");
        String source = ""
                + "package sample.springling;\n"
                + "import com.lingframe.api.context.LingContext;\n"
                + "import com.lingframe.api.ling.Ling;\n"
                + "import org.springframework.boot.autoconfigure.SpringBootApplication;\n"
                + "import org.springframework.context.annotation.Bean;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n"
                + "import java.util.concurrent.ExecutorService;\n"
                + "import java.util.concurrent.Executors;\n"
                + "@SpringBootApplication\n"
                + "public class SampleLingApp implements Ling {\n"
                + "    public static void main(String[] args) {\n"
                + "        org.springframework.boot.SpringApplication.run(SampleLingApp.class, args);\n"
                + "    }\n"
                + "    @Override\n"
                + "    public void onStart(LingContext context) {\n"
                + "    }\n"
                + "    @Override\n"
                + "    public void onStop(LingContext context) {\n"
                + "    }\n"
                + "    @Bean\n"
                + "    public ExecutorService lingExecutor() {\n"
                + "        return Executors.newSingleThreadExecutor(r -> {\n"
                + "            Thread thread = new Thread(r, \"sample-ling-executor\");\n"
                + "            thread.setDaemon(true);\n"
                + "            return thread;\n"
                + "        });\n"
                + "    }\n"
                + "    @RestController\n"
                + "    static class DemoController {\n"
                + "        @GetMapping(\"/demo/ping\")\n"
                + "        public String ping() {\n"
                + "            return \"pong\";\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null,
                StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classesDir.toFile()));
            boolean success = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    Arrays.asList("-classpath", System.getProperty("java.class.path")),
                    null,
                    fileManager.getJavaFileObjects(sourceFile.toFile()))
                    .call();
            if (!success) {
                throw new IllegalStateException("Failed to compile Spring ling app");
            }
        }
    }

    /**
     * 总闸断言：ClassLoader 应在卸载后被 GC 回收。
     */
    private static void assertClassLoaderCollected(boolean collected, ClassLoader leakedLoader) {
        if (!collected) {
            System.err.println("[DIAG] classLoaderCollected=false。"
                    + "JVM inputArguments: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
            diagnoseClassLoaderLeak(leakedLoader);
        }
        assertTrue(collected, "ClassLoader 应在生产卸载链路后被 GC 回收，存在静态缓存泄漏");
    }

    /**
     * 诊断：扫描灵核侧静态字段，找出谁持有泄漏的 ClassLoader。
     */
    @SuppressWarnings("unchecked")
    private static void diagnoseClassLoaderLeak(ClassLoader leakedLoader) {
        if (leakedLoader == null) {
            System.err.println("[DIAG] leakedLoader is null, cannot diagnose");
            return;
        }
        IdentityHashMap<Object, String> visited = new IdentityHashMap<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        for (Class<?> cls : findAllLoadedLingFrameClasses()) {
            for (Field f : cls.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) {
                        stack.push(new Object[] { val, cls.getName() + "." + f.getName() });
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        int found = 0;
        while (!stack.isEmpty() && found < 20) {
            Object[] entry = stack.pop();
            Object node = entry[0];
            String path = (String) entry[1];
            if (visited.containsKey(node)) {
                continue;
            }
            visited.put(node, path);
            if (node == leakedLoader) {
                System.err.println("[DIAG-LEAK] 持有链: " + path);
                found++;
                continue;
            }
            if (node.getClass().getName().startsWith("com.lingframe") || node instanceof Collection
                    || node instanceof Map || node.getClass().isArray()) {
                Class<?> c = node.getClass();
                while (c != null && c != Object.class) {
                    for (Field f : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        try {
                            f.setAccessible(true);
                            Object val = f.get(node);
                            if (val != null && !visited.containsKey(val)) {
                                stack.push(new Object[] { val, path + "." + f.getName() });
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    c = c.getSuperclass();
                }
                if (node instanceof Collection) {
                    int i = 0;
                    for (Object el : (Collection<?>) node) {
                        if (el != null && !visited.containsKey(el)) {
                            stack.push(new Object[] { el, path + "[" + (i++) + "]" });
                        }
                    }
                } else if (node instanceof Map) {
                    for (Object e : ((Map<?, ?>) node).entrySet()) {
                        Map.Entry<?, ?> en = (Map.Entry<?, ?>) e;
                        Object k = en.getKey(), v = en.getValue();
                        if (k != null && !visited.containsKey(k)) {
                            stack.push(new Object[] { k, path + "{key}" });
                        }
                        if (v != null && !visited.containsKey(v)) {
                            stack.push(new Object[] { v, path + "{val}" });
                        }
                    }
                } else if (node.getClass().isArray() && !node.getClass().getComponentType().isPrimitive()) {
                    int len = java.lang.reflect.Array.getLength(node);
                    for (int i = 0; i < len; i++) {
                        Object el = java.lang.reflect.Array.get(node, i);
                        if (el != null && !visited.containsKey(el)) {
                            stack.push(new Object[] { el, path + "[" + i + "]" });
                        }
                    }
                }
            }
        }
        if (found == 0) {
            System.err.println("[DIAG] 未在灵核静态字段中找到直接持有链，可能泄漏源在实例字段或线程局部");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Class<?>> findAllLoadedLingFrameClasses() {
        List<Class<?>> result = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        ClassLoader cur = cl;
        while (cur != null) {
            try {
                Field classesField = ClassLoader.class.getDeclaredField("classes");
                classesField.setAccessible(true);
                Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(cur);
                if (classes != null) {
                    for (Class<?> c : classes) {
                        if (c.getName().startsWith("com.lingframe")) {
                            result.add(c);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            cur = cur.getParent();
        }
        return result;
    }

    private Object bootShutdownHook() throws Exception {
        Field field = Class.forName("org.springframework.boot.SpringApplication").getDeclaredField("shutdownHook");
        field.setAccessible(true);
        return field.get(null);
    }

    private String findThreadContextClassLoaderReference(ClassLoader targetClassLoader) {
        if (targetClassLoader == null) {
            return null;
        }
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != null && thread.getContextClassLoader() == targetClassLoader) {
                return "thread.contextClassLoader:" + thread.getName();
            }
        }
        return null;
    }

    private HandlerExecutionChain getHandlerExecutionChain(RequestMappingHandlerMapping mapping, Object request)
            throws Exception {
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> requestIntf = findServletInterface(cl, "HttpServletRequest");
        Method getHandlerMethod = ReflectionUtils.findMethod(mapping.getClass(), "getHandler", requestIntf);
        if (getHandlerMethod == null) {
            throw new AssertionError("Cannot resolve RequestMappingHandlerMapping.getHandler for request type "
                    + request.getClass().getName());
        }
        return (HandlerExecutionChain) ReflectionUtils.invokeMethod(getHandlerMethod, mapping, request);
    }

    private String performWebRequest(TestHost host, String routePath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", routePath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerExecutionChain chain = getHandlerExecutionChain(host.hostMapping, request);
        assertNotNull(chain);
        host.hostAdapter.handle(request, response, chain.getHandler());
        return response.getContentAsString();
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

    // ==================== 内部支撑类 ====================

    private static final class TestHost implements AutoCloseable {
        private final GenericApplicationContext hostContext;
        private final RequestMappingHandlerMapping hostMapping;
        private final RequestMappingHandlerAdapter hostAdapter;
        private final WebInterfaceManager manager;

        private TestHost(GenericApplicationContext hostContext,
                RequestMappingHandlerMapping hostMapping,
                RequestMappingHandlerAdapter hostAdapter,
                WebInterfaceManager manager) {
            this.hostContext = hostContext;
            this.hostMapping = hostMapping;
            this.hostAdapter = hostAdapter;
            this.manager = manager;
        }

        private static TestHost create() throws Exception {
            GenericApplicationContext hostContext = new GenericApplicationContext();
            hostContext.refresh();

            RequestMappingHandlerAdapter hostAdapter = new RequestMappingHandlerAdapter();
            hostAdapter.setApplicationContext(hostContext);
            hostAdapter.setMessageConverters(Collections.singletonList(new StringHttpMessageConverter()));
            hostAdapter.afterPropertiesSet();

            RequestMappingHandlerMapping hostMapping = new RequestMappingHandlerMapping();
            hostMapping.setApplicationContext(hostContext);
            hostMapping.afterPropertiesSet();

            WebInterfaceManager manager = new WebInterfaceManager(null, null, null);
            manager.init(hostMapping, hostAdapter, hostContext);
            return new TestHost(hostContext, hostMapping, hostAdapter, manager);
        }

        @Override
        public void close() {
            manager.shutdown();
            hostContext.close();
        }
    }

    /**
     * 跟踪 close 状态的 LingClassLoader（生产用 LingClassLoader，非 URLClassLoader）。
     */
    private static final class CloseAwareLingClassLoader extends LingClassLoader {
        private final AtomicBoolean closed;

        private CloseAwareLingClassLoader(String lingId, URL[] urls, ClassLoader parent, AtomicBoolean closed) {
            super(lingId, urls, parent);
            this.closed = closed;
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    private static final class CycleResult {
        final boolean classLoaderClosed;
        final boolean runtimeStillRegistered;
        final boolean routeRemoved;
        final boolean noThreadContextClassLoaderLeak;
        final boolean classLoaderCollected;
        final ClassLoader leakedClassLoader;

        private CycleResult(boolean classLoaderClosed, boolean runtimeStillRegistered, boolean routeRemoved,
                boolean noThreadContextClassLoaderLeak, boolean classLoaderCollected, ClassLoader leakedClassLoader) {
            this.classLoaderClosed = classLoaderClosed;
            this.runtimeStillRegistered = runtimeStillRegistered;
            this.routeRemoved = routeRemoved;
            this.noThreadContextClassLoaderLeak = noThreadContextClassLoaderLeak;
            this.classLoaderCollected = classLoaderCollected;
            this.leakedClassLoader = leakedClassLoader;
        }
    }
}
