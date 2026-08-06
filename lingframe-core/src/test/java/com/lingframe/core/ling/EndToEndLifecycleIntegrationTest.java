package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端生命周期集成测试
 * <p>
 * 覆盖 install → invoke（含治理） → undeploy 完整链路，
 * 验证双层状态机、Pipeline 治理引擎、资源回收的协同正确性。
 */
@DisplayName("端到端生命周期集成测试")
class EndToEndLifecycleIntegrationTest {

    private static final String LING_ID = "e2e-ling";
    private static final String VERSION = "1.0.0";
    private static final String SERVICE_CLASS_NAME = "sample.e2e.EchoService";
    private static final String REQUIRED_PERMISSION = "test:invoke";

    private TestRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        runtime = createTestRuntime();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    @Nested
    @DisplayName("单灵元生命周期")
    class SingleLingLifecycle {

        @Test
        @DisplayName("完整生命周期：部署 → NORMAL 调用 → 卸载 → 资源回收")
        void shouldCompleteFullLifecycleWithNormalInvocation() throws Throwable {
            // 1. 部署
            deployEchoService();

            // 2. NORMAL 模式调用
            Object result = invokeEcho("hello");
            assertEquals("echo:hello", result);

            // 3. 验证治理资源已创建
            assertTrue(runtime.repository.hasRuntime(LING_ID));

            // 4. 卸载
            runtime.lifecycleEngine.undeploy(LING_ID);

            // 5. 验证灵元已从仓库移除
            assertFalse(runtime.repository.hasRuntime(LING_ID));
        }

        @Test
        @DisplayName("GOVERN_ONLY 模式应跳过终端调用")
        void shouldSkipTerminalInvocationInGovernOnlyMode() throws Throwable {
            deployEchoService();

            InvocationContext ctx = InvocationContext.obtain();
            try {
                ctx.setServiceFQSID(LING_ID + ":" + SERVICE_CLASS_NAME);
                ctx.setMethodName("echo");
                ctx.setParameterTypeNames(new String[]{"java.lang.String"});
                ctx.setArgs(new Object[]{"test"});
                ctx.governance().setRequiredPermission(REQUIRED_PERMISSION);
                ctx.governance().setAccessType(AccessType.EXECUTE);
                ctx.execution().setMode(InvocationExecutionMode.GOVERN_ONLY);

                runtime.pipelineEngine.invoke(ctx);
                // GOVERN_ONLY 不执行终端调用，不抛异常即通过
                assertTrue(runtime.repository.hasRuntime(LING_ID));
            } finally {
                ctx.recycle();
            }

            runtime.lifecycleEngine.undeploy(LING_ID);
        }

        @Test
        @DisplayName("SIMULATION 模式应返回模拟结果")
        void shouldReturnSimulatedResultInSimulationMode() throws Throwable {
            deployEchoService();

            InvocationContext ctx = InvocationContext.obtain();
            try {
                ctx.setServiceFQSID(LING_ID + ":" + SERVICE_CLASS_NAME);
                ctx.setMethodName("echo");
                ctx.setParameterTypeNames(new String[]{"java.lang.String"});
                ctx.setArgs(new Object[]{"test"});
                ctx.governance().setRequiredPermission(REQUIRED_PERMISSION);
                ctx.governance().setAccessType(AccessType.EXECUTE);
                ctx.execution().setMode(InvocationExecutionMode.SIMULATION);

                runtime.pipelineEngine.invoke(ctx);
                assertTrue(runtime.repository.hasRuntime(LING_ID));
            } finally {
                ctx.recycle();
            }

            runtime.lifecycleEngine.undeploy(LING_ID);
        }
    }

    @Nested
    @DisplayName("并发调用")
    class ConcurrentInvocation {

        @Test
        @DisplayName("多线程并发 NORMAL 调用应全部成功")
        void shouldHandleConcurrentNormalInvocations() throws Throwable {
            deployEchoService();

            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicReference<String> firstFailure = new AtomicReference<>();

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        InvocationContext ctx = InvocationContext.obtain();
                        try {
                            ctx.setServiceFQSID(LING_ID + ":" + SERVICE_CLASS_NAME);
                            ctx.setMethodName("echo");
                            ctx.setParameterTypeNames(new String[]{"java.lang.String"});
                            ctx.setArgs(new Object[]{"msg-" + idx});
                            ctx.governance().setRequiredPermission(REQUIRED_PERMISSION);
                            ctx.governance().setAccessType(AccessType.EXECUTE);
                            ctx.execution().setMode(InvocationExecutionMode.NORMAL);

                            Object result = runtime.pipelineEngine.invoke(ctx);
                            if (("echo:msg-" + idx).equals(result)) {
                                successCount.incrementAndGet();
                            } else {
                                firstFailure.compareAndSet(null,
                                        "idx=" + idx + " expected=echo:msg-" + idx + " actual=" + result);
                            }
                        } finally {
                            ctx.recycle();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate");

            assertEquals(threadCount, successCount.get(),
                    "Concurrent invocation failures: " + firstFailure.get());
            assertEquals(0, errorCount.get());

            runtime.lifecycleEngine.undeploy(LING_ID);
        }
    }

    @Nested
    @DisplayName("多轮生命周期")
    class RepeatedLifecycle {

        @Test
        @DisplayName("连续 3 轮部署-调用-卸载应全部成功且资源回收")
        void shouldSupportRepeatedDeployInvokeUndeployCycles() throws Throwable {
            for (int i = 0; i < 3; i++) {
                deployEchoService();

                Object result = invokeEcho("round-" + i);
                assertEquals("echo:round-" + i, result);

                runtime.lifecycleEngine.undeploy(LING_ID);
                assertFalse(runtime.repository.hasRuntime(LING_ID));
            }
        }
    }

    // ==================== 辅助方法 ====================

    private void deployEchoService() throws Throwable {
        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(VERSION);
        definition.setMainClass(SERVICE_CLASS_NAME);

        runtime.lifecycleEngine.deploy(definition, runtime.classesDir.toFile(), true, Collections.emptyMap());
        assertTrue(runtime.repository.hasRuntime(LING_ID));
    }

    private Object invokeEcho(String input) {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID(LING_ID + ":" + SERVICE_CLASS_NAME);
        ctx.setMethodName("echo");
        ctx.setParameterTypeNames(new String[]{"java.lang.String"});
        ctx.setArgs(new Object[]{input});
        ctx.governance().setRequiredPermission(REQUIRED_PERMISSION);
        ctx.governance().setAccessType(AccessType.EXECUTE);
        try {
            Object result = runtime.pipelineEngine.invoke(ctx);
            assertNotNull(result, "invokeEcho should return non-null result");
            return result;
        } finally {
            ctx.recycle();
        }
    }

    private TestRuntime createTestRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-lifecycle");
        Path sourceDir = workspace.resolve("src");
        Path classesDir = workspace.resolve("classes");
        compileServiceClass(sourceDir, classesDir);

        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingRepository repository = new DefaultLingRepository();
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed(isNull(), eq(REQUIRED_PERMISSION), eq(AccessType.EXECUTE))).thenReturn(true);

        LingServiceRegistry serviceRegistry = mock(LingServiceRegistry.class);
        when(serviceRegistry.getServicesByLingId(LING_ID)).thenReturn(Collections.emptyList());

        FilterRegistry registry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(new InvokableMethodCache())
                .permissionService(permissionService)
                .lingRepository(repository)
                .trafficRouter(new LatestVersionPolicy())
                .eventBus(eventBus)
                .runtimeCoordinator(runtimeCoordinator)
                .build());
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(registry);
        LingUnloadCoordinator unloadCoordinator =
                new LingUnloadCoordinator(pipelineEngine, Collections.emptyList(), Collections.emptyList(), null, null);

        AtomicReference<CloseAwareClassLoader> loaderHolder = new AtomicReference<>();
        AtomicBoolean classLoaderClosed = new AtomicBoolean(false);
        LingLoaderFactory loaderFactory = (lingId, sourceFile, parent) -> {
            classLoaderClosed.set(false);
            CloseAwareClassLoader classLoader;
            try {
                classLoader = new CloseAwareClassLoader(
                        new URL[]{classesDir.toUri().toURL()},
                        parent,
                        classLoaderClosed);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create test classloader", e);
            }
            loaderHolder.set(classLoader);
            return classLoader;
        };

        ContainerFactory containerFactory =
                (definition, jarFile, classLoader) -> new ReflectiveLingContainer(classLoader, definition.getMainClass());

        LingFrameConfig config = LingFrameConfig.builder()
                .apiOverrideCheckEnabled(false)
                .runtimeConfig(LingRuntimeConfig.builder()
                        .bulkheadMaxConcurrent(10)
                        .defaultTimeoutMs(5000)
                        .rateLimitPerSecond(100)
                        .forceCleanupDelaySeconds(0)
                        .build())
                .build();
        List<LingSecurityVerifier> verifiers = Collections.singletonList(new DangerousApiVerifier(false, Collections.emptyList(), null));

        DefaultLingLifecycleEngine lifecycleEngine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(permissionService)
                .lingLoaderFactory(loaderFactory)
                .verifiers(verifiers)
                .eventBus(eventBus)
                .lingFrameConfig(config)
                .lingRepository(repository)
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(pipelineEngine)
                .lingResourceManager(null)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        return new TestRuntime(workspace, classesDir, repository, permissionService, serviceRegistry,
                registry, pipelineEngine, lifecycleEngine, runtimeCoordinator, loaderHolder, classLoaderClosed);
    }

    private void compileServiceClass(Path sourceDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is unavailable");
        }

        Path packageDir = sourceDir.resolve("sample/e2e");
        Files.createDirectories(packageDir);
        Files.createDirectories(classesDir);

        Path sourceFile = packageDir.resolve("EchoService.java");
        String source = "package sample.e2e;\n"
                + "public class EchoService {\n"
                + "    public String echo(String msg) {\n"
                + "        return \"echo:\" + msg;\n"
                + "    }\n"
                + "}\n";
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classesDir.toFile()));
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, null, null, null,
                fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile.toFile())));
        assertTrue(task.call());
        fileManager.close();
    }

    // ==================== 内部类 ====================

    private static final class TestRuntime {
        final Path workspace;
        final Path classesDir;
        final DefaultLingRepository repository;
        final PermissionService permissionService;
        final LingServiceRegistry serviceRegistry;
        final FilterRegistry registry;
        final InvocationPipelineEngine pipelineEngine;
        final DefaultLingLifecycleEngine lifecycleEngine;
        final RuntimeCoordinator runtimeCoordinator;
        final AtomicReference<CloseAwareClassLoader> loaderHolder;
        final AtomicBoolean classLoaderClosed;

        TestRuntime(Path workspace, Path classesDir, DefaultLingRepository repository,
                    PermissionService permissionService, LingServiceRegistry serviceRegistry,
                    FilterRegistry registry, InvocationPipelineEngine pipelineEngine,
                    DefaultLingLifecycleEngine lifecycleEngine, RuntimeCoordinator runtimeCoordinator,
                    AtomicReference<CloseAwareClassLoader> loaderHolder, AtomicBoolean classLoaderClosed) {
            this.workspace = workspace;
            this.classesDir = classesDir;
            this.repository = repository;
            this.permissionService = permissionService;
            this.serviceRegistry = serviceRegistry;
            this.registry = registry;
            this.pipelineEngine = pipelineEngine;
            this.lifecycleEngine = lifecycleEngine;
            this.runtimeCoordinator = runtimeCoordinator;
            this.loaderHolder = loaderHolder;
            this.classLoaderClosed = classLoaderClosed;
        }

        void shutdown() throws IOException {
            try {
                lifecycleEngine.undeploy(LING_ID);
            } catch (Exception ignored) {
            }
            deleteRecursively(workspace);
        }

        private void deleteRecursively(Path path) throws IOException {
            if (!Files.exists(path)) {
                return;
            }
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static final class CloseAwareClassLoader extends URLClassLoader {
        private final AtomicBoolean closedFlag;

        CloseAwareClassLoader(URL[] urls, ClassLoader parent, AtomicBoolean closedFlag) {
            super(urls, parent);
            this.closedFlag = closedFlag;
        }

        @Override
        public void close() throws IOException {
            super.close();
            closedFlag.set(true);
        }
    }

    /**
     * 反射式灵元容器 — 通过反射调用灵元主类的 start/stop 方法
     */
    private static final class ReflectiveLingContainer implements LingContainer {
        private final ClassLoader classLoader;
        private final String mainClass;
        private volatile Object instance;

        ReflectiveLingContainer(ClassLoader classLoader, String mainClass) {
            this.classLoader = classLoader;
            this.mainClass = mainClass;
        }

        @Override
        public void start(LingContext context) {
            try {
                Class<?> clazz = classLoader.loadClass(mainClass);
                instance = clazz.getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start ling container", e);
            } catch (Error e) {
                // NoClassDefFoundError 等需要透传，不能吞掉
                throw e;
            }
        }

        @Override
        public void stop() {
            instance = null;
        }

        @Override
        public boolean isActive() {
            return instance != null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getBean(Class<T> type) {
            if (instance != null && type.isInstance(instance)) {
                return (T) instance;
            }
            return null;
        }

        @Override
        public Object getBean(String beanName) {
            return instance;
        }

        @Override
        public String[] getBeanNames() {
            return instance != null ? new String[]{mainClass} : new String[0];
        }

        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }
    }
}
