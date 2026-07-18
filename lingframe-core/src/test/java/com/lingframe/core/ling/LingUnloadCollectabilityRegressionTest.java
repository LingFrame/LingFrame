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
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.pipeline.ResilienceGovernanceFilter;
import com.lingframe.core.pipeline.ThreadIsolationGovernanceFilter;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Ling 卸载可回收回归测试")
class LingUnloadCollectabilityRegressionTest {

    private static final String LING_ID = "ling1";
    private static final String VERSION = "1.0.0";
    private static final String SERVICE_CLASS_NAME = "sample.ling.GreetingService";
    private static final String REQUIRED_PERMISSION = "test:invoke";

    @Test
    @DisplayName("应通过 DefaultLingLifecycleEngine 完成真实安装 调用 卸载与回收验证")
    void shouldInstallInvokeUndeployAndCollectThroughLifecycleEngine() throws Throwable {
        TestRuntime runtime = createTestRuntime();
        try {
            CycleResult cycle = runCycle(runtime, "hello", false);

            assertEquals("echo:hello", cycle.invocationResult);
            assertTrue(cycle.classLoaderClosed);
            assertFalse(cycle.runtimeStillRegistered);
            assertFalse(cycle.hasLimiter);
            assertFalse(cycle.hasBreaker);
            assertFalse(cycle.hasExecutor);
            assertClassLoaderCollected(cycle.classLoaderCollected);

            verify(runtime.serviceRegistry).evict(LING_ID);
            verify(runtime.permissionService).removeLing(LING_ID);
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    @DisplayName("应支持多轮真实安装 调用 卸载后持续可回收")
    void shouldRemainCollectibleAcrossRepeatedLifecycleCycles() throws Throwable {
        TestRuntime runtime = createTestRuntime();
        try {
            for (int i = 0; i < 3; i++) {
                CycleResult cycle = runCycle(runtime, "round-" + i, true);

                assertEquals("echo:round-" + i, cycle.invocationResult);
                assertTrue(cycle.classLoaderClosed);
                assertFalse(cycle.runtimeStillRegistered);
                assertFalse(cycle.hasLimiter);
                assertFalse(cycle.hasBreaker);
                assertFalse(cycle.hasExecutor);
                assertClassLoaderCollected(cycle.classLoaderCollected);
            }

            verify(runtime.serviceRegistry, times(3)).evict(LING_ID);
            verify(runtime.permissionService, times(3)).removeLing(LING_ID);
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    @DisplayName("应支持带预检返回的卸载链路且仍保持可回收")
    void shouldRemainCollectibleWhenUndeployWithReportReturnsPrecheckSummary() throws Throwable {
        TestRuntime runtime = createTestRuntime();
        try {
            CycleResult cycle = runCycle(runtime, "with-report", false, true);

            assertEquals("echo:with-report", cycle.invocationResult);
            assertTrue(cycle.classLoaderClosed);
            assertFalse(cycle.runtimeStillRegistered);
            assertFalse(cycle.hasLimiter);
            assertFalse(cycle.hasBreaker);
            assertFalse(cycle.hasExecutor);
            assertClassLoaderCollected(cycle.classLoaderCollected);
            assertEquals(LeakRiskLevel.CHECK_FAILED, cycle.overallRiskLevel);
            assertTrue(cycle.uninstallTriggered);

            verify(runtime.serviceRegistry).evict(LING_ID);
            verify(runtime.permissionService).removeLing(LING_ID);
        } finally {
            runtime.shutdown();
        }
    }

    private TestRuntime createTestRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("ling-lifecycle-regression");
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
                new LingUnloadCoordinator(pipelineEngine, Collections.emptyList(), null, null);

        AtomicReference<CloseAwareClassLoader> loaderHolder = new AtomicReference<>();
        AtomicBoolean classLoaderClosed = new AtomicBoolean(false);
        LingLoaderFactory loaderFactory = (lingId, sourceFile, parent) -> {
            classLoaderClosed.set(false);
            CloseAwareClassLoader classLoader;
            try {
                classLoader = new CloseAwareClassLoader(
                        new URL[] { classesDir.toUri().toURL() },
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
                        .bulkheadMaxConcurrent(1)
                        .defaultTimeoutMs(1000)
                        .rateLimitPerSecond(5)
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

        return new TestRuntime(workspace, classesDir, repository, permissionService, serviceRegistry, registry,
                pipelineEngine, lifecycleEngine, runtimeCoordinator, loaderHolder, classLoaderClosed);
    }

    private CycleResult runCycle(TestRuntime runtime, String input, boolean repeated) throws Throwable {
        return runCycle(runtime, input, repeated, false);
    }

    private CycleResult runCycle(TestRuntime runtime, String input, boolean repeated, boolean useUndeployWithReport)
            throws Throwable {
        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(VERSION);
        definition.setMainClass(SERVICE_CLASS_NAME);

        runtime.lifecycleEngine.deploy(definition, runtime.classesDir.toFile(), true, Collections.emptyMap());

        CloseAwareClassLoader targetClassLoader = runtime.loaderHolder.get();
        assertNotNull(targetClassLoader);
        WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(targetClassLoader);

        Object invocationResult = invokeBusinessMethod(runtime.pipelineEngine, input);
        if (repeated) {
            assertTrue(runtime.repository.hasRuntime(LING_ID));
        }

        ResilienceGovernanceFilter resilienceFilter = invokeNoArg(runtime.registry, "getResilienceFilter");
        ThreadIsolationGovernanceFilter isolationFilter = invokeNoArg(runtime.registry, "getIsolationFilter");
        assertNotNull(resilienceFilter);
        assertNotNull(isolationFilter);
        assertTrue(invokeBoolean(resilienceFilter, "hasLimiter", LING_ID));
        assertTrue(invokeBoolean(resilienceFilter, "hasBreaker", LING_ID));
        assertTrue(invokeBoolean(isolationFilter, "hasExecutor", LING_ID));

        LeakRiskLevel overallRiskLevel = null;
        boolean uninstallTriggered = false;
        if (useUndeployWithReport) {
            LingUninstallResult uninstallResult = runtime.lifecycleEngine.undeployWithReport(LING_ID);
            overallRiskLevel = uninstallResult.getOverallRiskLevel();
            uninstallTriggered = uninstallResult.isUninstallTriggered();
        } else {
            runtime.lifecycleEngine.undeploy(LING_ID);
        }

        boolean hasLimiter = invokeBoolean(resilienceFilter, "hasLimiter", LING_ID);
        boolean hasBreaker = invokeBoolean(resilienceFilter, "hasBreaker", LING_ID);
        boolean hasExecutor = invokeBoolean(isolationFilter, "hasExecutor", LING_ID);
        boolean runtimeStillRegistered = runtime.repository.hasRuntime(LING_ID);
        boolean classLoaderClosed = runtime.classLoaderClosed.get();

        targetClassLoader = null;
        runtime.loaderHolder.set(null);
        awaitCollection(classLoaderRef);

        return new CycleResult(
                invocationResult,
                classLoaderClosed,
                runtimeStillRegistered,
                hasLimiter,
                hasBreaker,
                hasExecutor,
                classLoaderRef.get() == null,
                overallRiskLevel,
                uninstallTriggered);
    }

    private Object invokeBusinessMethod(InvocationPipelineEngine pipelineEngine, String input) {
        InvocationContext context = InvocationContext.obtain();
        context.setServiceFQSID(LING_ID + ":" + SERVICE_CLASS_NAME);
        context.setMethodName("echo");
        context.setParameterTypeNames(new String[] { "java.lang.String" });
        context.setArgs(new Object[] { input });
        context.governance().setRequiredPermission(REQUIRED_PERMISSION);
        context.governance().setAccessType(AccessType.EXECUTE);
        try {
            return pipelineEngine.invoke(context);
        } finally {
            context.recycle();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (T) method.invoke(target);
    }

    private boolean invokeBoolean(Object target, String methodName, String lingId) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(target, lingId);
    }

    private void awaitCollection(WeakReference<ClassLoader> reference) throws InterruptedException {
        for (int i = 0; i < 20 && reference.get() != null; i++) {
            System.gc();
            System.runFinalization();
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    /**
     * 断言 ClassLoader 已被 GC 回收。
     */
    private static void assertClassLoaderCollected(boolean collected) {
        if (!collected) {
            // 诊断：打印 JVM 启动参数，帮助定位 ClassLoader 泄漏原因
            System.err.println("[DIAG] classLoaderCollected=false。"
                    + "JVM inputArguments: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        }
        assertTrue(collected, "ClassLoader 应在 undeploy 后被 GC 回收，存在泄漏");
    }

    private void compileServiceClass(Path sourceDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is unavailable");
        }

        Path packageDir = sourceDir.resolve("sample/ling");
        Files.createDirectories(packageDir);
        Files.createDirectories(classesDir);

        Path sourceFile = packageDir.resolve("GreetingService.java");
        String source = ""
                + "package sample.ling;\n"
                + "public class GreetingService {\n"
                + "    public String echo(String input) {\n"
                + "        return \"echo:\" + input;\n"
                + "    }\n"
                + "}\n";
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classesDir.toFile()));
            boolean success = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    null,
                    null,
                    fileManager.getJavaFileObjects(sourceFile.toFile()))
                    .call();
            if (!success) {
                throw new IllegalStateException("Failed to compile test ling class");
            }
        }
    }

    private static final class ReflectiveLingContainer implements LingContainer {
        private final ClassLoader classLoader;
        private final String mainClassName;
        private volatile boolean active;
        private volatile Object bean;

        private ReflectiveLingContainer(ClassLoader classLoader, String mainClassName) {
            this.classLoader = classLoader;
            this.mainClassName = mainClassName;
        }

        @Override
        public void start(LingContext context) {
            try {
                Class<?> targetClass = classLoader.loadClass(mainClassName);
                this.bean = targetClass.getDeclaredConstructor().newInstance();
                this.active = true;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to start reflective ling container", e);
            }
        }

        @Override
        public void stop() {
            this.active = false;
            this.bean = null;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getBean(Class<T> type) {
            if (bean != null && type.isInstance(bean)) {
                return (T) bean;
            }
            return null;
        }

        @Override
        public Object getBean(String beanName) {
            return bean;
        }

        @Override
        public String[] getBeanNames() {
            return bean == null ? new String[0] : new String[] { mainClassName };
        }

        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
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

    private static final class TestRuntime {
        private final Path workspace;
        private final Path classesDir;
        private final DefaultLingRepository repository;
        private final PermissionService permissionService;
        private final LingServiceRegistry serviceRegistry;
        private final FilterRegistry registry;
        private final InvocationPipelineEngine pipelineEngine;
        private final DefaultLingLifecycleEngine lifecycleEngine;
        private final RuntimeCoordinator runtimeCoordinator;
        private final AtomicReference<CloseAwareClassLoader> loaderHolder;
        private final AtomicBoolean classLoaderClosed;

        private TestRuntime(Path workspace,
                Path classesDir,
                DefaultLingRepository repository,
                PermissionService permissionService,
                LingServiceRegistry serviceRegistry,
                FilterRegistry registry,
                InvocationPipelineEngine pipelineEngine,
                DefaultLingLifecycleEngine lifecycleEngine,
                RuntimeCoordinator runtimeCoordinator,
                AtomicReference<CloseAwareClassLoader> loaderHolder,
                AtomicBoolean classLoaderClosed) {
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

        private void shutdown() throws IOException {
            runtimeCoordinator.stop();
            deleteRecursively(workspace);
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

    private static final class CycleResult {
        private final Object invocationResult;
        private final boolean classLoaderClosed;
        private final boolean runtimeStillRegistered;
        private final boolean hasLimiter;
        private final boolean hasBreaker;
        private final boolean hasExecutor;
        private final boolean classLoaderCollected;
        private final LeakRiskLevel overallRiskLevel;
        private final boolean uninstallTriggered;

        private CycleResult(Object invocationResult,
                            boolean classLoaderClosed,
                            boolean runtimeStillRegistered,
                            boolean hasLimiter,
                            boolean hasBreaker,
                            boolean hasExecutor,
                            boolean classLoaderCollected,
                            LeakRiskLevel overallRiskLevel,
                            boolean uninstallTriggered) {
            this.invocationResult = invocationResult;
            this.classLoaderClosed = classLoaderClosed;
            this.runtimeStillRegistered = runtimeStillRegistered;
            this.hasLimiter = hasLimiter;
            this.hasBreaker = hasBreaker;
            this.hasExecutor = hasExecutor;
            this.classLoaderCollected = classLoaderCollected;
            this.overallRiskLevel = overallRiskLevel;
            this.uninstallTriggered = uninstallTriggered;
        }
    }
}
