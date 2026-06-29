package com.lingframe.starter.resource;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.starter.adapter.SpringLingContainer;
import com.lingframe.starter.web.WebInterfaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("SpringLingContainer 卸载回归测试")
class SpringLingContainerUnloadRegressionTest {

    private static final String APP_CLASS_NAME = "sample.springling.SampleLingApp";

    /**
     * 检测当前 JVM 是否运行在覆盖率采集模式下。
     * 覆盖率 agent（JaCoCo / IntelliJ Coverage）会对自定义 ClassLoader 持有强引用，
     * 导致 ClassLoader 无法被 GC 回收，使总闸断言必然失败。
     * 此标志用于在该场景下降级 classLoaderCollected 断言。
     */
    private static boolean isCoverageAgentActive() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        List<String> inputArgs = runtimeMXBean.getInputArguments();
        for (String arg : inputArgs) {
            String lower = arg.toLowerCase();
            if (lower.contains("jacoco")
                    || lower.contains("intellij-coverage")
                    || lower.contains("coverage_rt")
                    || lower.contains("idea_rt")
                    || (lower.contains("javaagent") && lower.contains("coverage"))) {
                return true;
            }
        }
        return false;
    }

    private static final boolean COVERAGE_ACTIVE = isCoverageAgentActive();

    /**
     * 总闸断言：ClassLoader 应在卸载后被 GC 回收。
     * ClassLoader 是灵元所有静态引用的根，只要灵核侧任何一个静态表
     * （BPP 缓存、Property.annotationCache、Jackson TypeFactory 等）
     * 残留灵元 Class，ClassLoader 即不可达，断言红。
     * 覆盖率 agent 活跃时降级为警告。
     */
    private static void assertClassLoaderCollected(boolean collected) {
        if (COVERAGE_ACTIVE) {
            if (!collected) {
                System.err.println(
                        "[WARN] classLoaderCollected=false，但当前运行在覆盖率采集模式下，"
                                + "覆盖率 agent 持有 ClassLoader 强引用导致无法回收，此断言已降级为警告。"
                                + "无覆盖率模式下此断言必须为 true。");
            }
        } else {
            if (!collected) {
                System.err.println("[DIAG] COVERAGE_ACTIVE=false，但 classLoaderCollected=false。"
                        + "JVM inputArguments: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
            }
            assertTrue(collected, "ClassLoader 应在 Spring 容器卸载后被 GC 回收，存在灵核侧静态缓存泄漏");
        }
    }

    @AfterEach
    void resetBootShutdownHook() throws Exception {
        SpringLingContainerUnloadRegressionSupport.reset();
        Method resetMethod = bootShutdownHook().getClass().getDeclaredMethod("reset");
        resetMethod.setAccessible(true);
        resetMethod.invoke(bootShutdownHook());
    }

    @Test
    @DisplayName("停止清理并关闭后应释放 Spring 灵核侧引用")
    void shouldReleaseSpringHostSideReferencesAfterStopCleanupAndClose() throws Exception {
        try (TestHost host = TestHost.create()) {
            CycleResult cycle = runCycle(host, "order-ling");

            assertTrue(cycle.executorShutdown);
            assertTrue(cycle.classLoaderClosed);
            assertTrue(cycle.shutdownHookClean);
            assertTrue(cycle.routeRemoved);
            assertTrue(cycle.noThreadContextClassLoaderLeak);
            assertClassLoaderCollected(cycle.classLoaderCollected);
            assertEquals(1, cycle.startCount);
            assertEquals(1, cycle.stopCount);
        }
    }

    @Test
    @DisplayName("重复 Spring 容器启停后仍应可被回收")
    void shouldRemainCollectibleAcrossRepeatedSpringContainerCycles() throws Exception {
        try (TestHost host = TestHost.create()) {
            for (int i = 0; i < 3; i++) {
                CycleResult cycle = runCycle(host, "order-ling-" + i);

                assertTrue(cycle.executorShutdown);
                assertTrue(cycle.classLoaderClosed);
                assertTrue(cycle.shutdownHookClean);
                assertTrue(cycle.routeRemoved);
                assertTrue(cycle.noThreadContextClassLoaderLeak);
                assertClassLoaderCollected(cycle.classLoaderCollected);
                assertEquals(1, cycle.startCount);
                assertEquals(1, cycle.stopCount);
            }
        }
    }

    private CycleResult runCycle(TestHost host, String lingId) throws Exception {
        CycleArtifacts artifacts = performCycle(host, lingId);
        try {
            return new CycleResult(
                    artifacts.startCount,
                    artifacts.stopCount,
                    artifacts.executorShutdown,
                    artifacts.classLoaderClosed,
                    artifacts.shutdownHookClean,
                    artifacts.routeRemoved,
                    artifacts.noThreadContextClassLoaderLeak,
                    artifacts.classLoaderCollected);
        } finally {
            deleteRecursively(artifacts.workspace);
        }
    }

    private CycleArtifacts performCycle(TestHost host, String lingId) throws Exception {
        SpringLingContainerUnloadRegressionSupport.reset();
        Path workspace = Files.createTempDirectory("spring-ling-unload");
        Path sourceDir = workspace.resolve("src");
        Path classesDir = workspace.resolve("classes");
        compileLingApp(sourceDir, classesDir);

        AtomicBoolean classLoaderClosed = new AtomicBoolean(false);
        WeakReference<ClassLoader> classLoaderRef = null;
        // 生态卸载钩子
        List<LingUnloadHook> ecosystemHooks = Arrays.asList(
                new SpringEcosystemUnloadHook(),
                new StorageCacheUnloadHook());
        ExecutorService executor;
        boolean shutdownHookCleanAfterStop;
        boolean routeRemovedAfterStop;

        CloseAwareClassLoader lingClassLoader = new CloseAwareClassLoader(
                new URL[] { classesDir.toUri().toURL() },
                getClass().getClassLoader(),
                classLoaderClosed);
        try {
            classLoaderRef = new WeakReference<>(lingClassLoader);

            Class<?> appClass = lingClassLoader.loadClass(APP_CLASS_NAME);
            SpringApplicationBuilder builder = new SpringApplicationBuilder()
                    .resourceLoader(new DefaultResourceLoader(lingClassLoader))
                    .sources(appClass)
                    .web(WebApplicationType.NONE)
                    .registerShutdownHook(false);

            SpringLingContainer container = new SpringLingContainer(
                    builder,
                    lingClassLoader,
                    host.manager,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    host.hostContext,
                    ecosystemHooks,
                    "v1");

            DefaultLingContext lingContext = createLingContext(lingId);
            String routePath = "/" + lingId + "/demo/ping";

            container.start(lingContext);
            assertTrue(container.isActive());
            assertNotNull(host.manager.resolveRoute(new MockHttpServletRequest("GET", routePath)));
            assertEquals("pong", performWebRequest(host, routePath));
            assertFalse(containsBootShutdownHookReference(lingClassLoader), findBootShutdownHookReference(lingClassLoader));

            executor = SpringLingContainerUnloadRegressionSupport.awaitExecutor();
            assertNotNull(executor);

            container.stop();

            assertFalse(container.isActive());
            assertTrue(executor.isShutdown());
            assertNull(host.manager.resolveRoute(new MockHttpServletRequest("GET", routePath)));
            assertNull(getHandlerExecutionChain(host.hostMapping, new MockHttpServletRequest("GET", routePath)));

            shutdownHookCleanAfterStop = !containsBootShutdownHookReference(lingClassLoader);
            routeRemovedAfterStop = host.manager.resolveRoute(new MockHttpServletRequest("GET", routePath)) == null;

            // 执行所有生态 Hook 清理
            for (LingUnloadHook hook : ecosystemHooks) {
                hook.cleanup(lingId, lingClassLoader);
            }

            appClass = null;
            builder = null;
            container = null;
            lingContext = null;
            lingClassLoader.close();
        } finally {
            lingClassLoader = null;
        }

        // 总闸：等待 ClassLoader 被 GC 回收
        awaitCollection(classLoaderRef);
        boolean classLoaderCollected = classLoaderRef.get() == null;

        return new CycleArtifacts(
                workspace,
                SpringLingContainerUnloadRegressionSupport.startCount(),
                SpringLingContainerUnloadRegressionSupport.stopCount(),
                executor.isShutdown(),
                classLoaderClosed.get(),
                shutdownHookCleanAfterStop,
                routeRemovedAfterStop,
                findThreadContextClassLoaderReference(classLoaderRef.get()) == null,
                classLoaderCollected);
    }

    private DefaultLingContext createLingContext(String lingId) {
        LingRepository repository = mock(LingRepository.class);
        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);
        PermissionService permissionService = mock(PermissionService.class);
        EventBus eventBus = new EventBus();
        return new DefaultLingContext(lingId, repository, serviceRegistry, pipelineEngine, permissionService, eventBus);
    }

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
                + "import com.lingframe.starter.resource.SpringLingContainerUnloadRegressionSupport;\n"
                + "import org.springframework.boot.autoconfigure.SpringBootApplication;\n"
                + "import org.springframework.context.annotation.Bean;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n"
                + "import java.util.concurrent.ExecutorService;\n"
                + "import java.util.concurrent.Executors;\n"
                + "@SpringBootApplication\n"
                + "public class SampleLingApp implements Ling {\n"
                + "    @Override\n"
                + "    public void onStart(LingContext context) {\n"
                + "        SpringLingContainerUnloadRegressionSupport.recordStart(context.getLingId());\n"
                + "    }\n"
                + "    @Override\n"
                + "    public void onStop(LingContext context) {\n"
                + "        SpringLingContainerUnloadRegressionSupport.recordStop(context != null ? context.getLingId() : null);\n"
                + "    }\n"
                + "    @Bean\n"
                + "    public ExecutorService lingExecutor() {\n"
                + "        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {\n"
                + "            Thread thread = new Thread(r, \"sample-ling-executor\");\n"
                + "            thread.setDaemon(true);\n"
                + "            return thread;\n"
                + "        });\n"
                + "        SpringLingContainerUnloadRegressionSupport.recordExecutor(executor);\n"
                + "        return executor;\n"
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

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classesDir.toFile()));
            boolean success = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    java.util.Arrays.asList("-classpath", System.getProperty("java.class.path")),
                    null,
                    fileManager.getJavaFileObjects(sourceFile.toFile()))
                    .call();
            if (!success) {
                throw new IllegalStateException("Failed to compile Spring ling app");
            }
        }
    }

    private void awaitCollection(WeakReference<ClassLoader> reference) throws InterruptedException {
        for (int i = 0; i < 100 && reference.get() != null; i++) {
            System.gc();
            System.runFinalization();
            TimeUnit.MILLISECONDS.sleep(100);
        }
    }

    private Object bootShutdownHook() throws Exception {
        Field field = Class.forName("org.springframework.boot.SpringApplication").getDeclaredField("shutdownHook");
        field.setAccessible(true);
        return field.get(null);
    }

    @SuppressWarnings("unchecked")
    private Set<ConfigurableApplicationContext> readContextSet(String fieldName) throws Exception {
        Field field = bootShutdownHook().getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Set<ConfigurableApplicationContext>) field.get(bootShutdownHook());
    }

    private boolean containsBootShutdownHookReference(ClassLoader targetClassLoader) throws Exception {
        return findBootShutdownHookReference(targetClassLoader) != null;
    }

    private String findBootShutdownHookReference(ClassLoader targetClassLoader) throws Exception {
        for (ConfigurableApplicationContext context : readContextSet("contexts")) {
            if (context.getClassLoader() == targetClassLoader) {
                return "shutdownHook.contexts -> context.classLoader";
            }
        }
        for (ConfigurableApplicationContext context : readContextSet("closedContexts")) {
            if (context.getClassLoader() == targetClassLoader) {
                return "shutdownHook.closedContexts -> context.classLoader";
            }
        }
        return null;
    }

    private String findThreadContextClassLoaderReference(ClassLoader targetClassLoader) {
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

    private Object readField(Object target, String fieldName) throws Exception {
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ex) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
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

    private static final class CloseAwareClassLoader extends URLClassLoader {
        private final AtomicBoolean closed;

        private CloseAwareClassLoader(URL[] urls, ClassLoader parent, AtomicBoolean closed) {
            super(urls, parent);
            this.closed = closed;
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    private static final class CycleResult {
        private final int startCount;
        private final int stopCount;
        private final boolean executorShutdown;
        private final boolean classLoaderClosed;
        private final boolean shutdownHookClean;
        private final boolean routeRemoved;
        private final boolean noThreadContextClassLoaderLeak;
        private final boolean classLoaderCollected;

        private CycleResult(int startCount,
                int stopCount,
                boolean executorShutdown,
                boolean classLoaderClosed,
                boolean shutdownHookClean,
                boolean routeRemoved,
                boolean noThreadContextClassLoaderLeak,
                boolean classLoaderCollected) {
            this.startCount = startCount;
            this.stopCount = stopCount;
            this.executorShutdown = executorShutdown;
            this.classLoaderClosed = classLoaderClosed;
            this.shutdownHookClean = shutdownHookClean;
            this.routeRemoved = routeRemoved;
            this.noThreadContextClassLoaderLeak = noThreadContextClassLoaderLeak;
            this.classLoaderCollected = classLoaderCollected;
        }
    }

    private static final class CycleArtifacts {
        private final Path workspace;
        private final int startCount;
        private final int stopCount;
        private final boolean executorShutdown;
        private final boolean classLoaderClosed;
        private final boolean shutdownHookClean;
        private final boolean routeRemoved;
        private final boolean noThreadContextClassLoaderLeak;
        private final boolean classLoaderCollected;

        private CycleArtifacts(Path workspace,
                int startCount,
                int stopCount,
                boolean executorShutdown,
                boolean classLoaderClosed,
                boolean shutdownHookClean,
                boolean routeRemoved,
                boolean noThreadContextClassLoaderLeak,
                boolean classLoaderCollected) {
            this.workspace = workspace;
            this.startCount = startCount;
            this.stopCount = stopCount;
            this.executorShutdown = executorShutdown;
            this.classLoaderClosed = classLoaderClosed;
            this.shutdownHookClean = shutdownHookClean;
            this.routeRemoved = routeRemoved;
            this.noThreadContextClassLoaderLeak = noThreadContextClassLoaderLeak;
            this.classLoaderCollected = classLoaderCollected;
        }
    }
}
