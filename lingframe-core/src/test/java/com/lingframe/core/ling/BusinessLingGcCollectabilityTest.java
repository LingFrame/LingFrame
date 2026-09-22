package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import com.lingframe.core.spi.LingUnloadHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 业务灵元（模式 2 私有库）挂载/卸载多轮循环可证 GC 测试。
 * <p>
 * 场景：业务灵元（私有库）每轮真实挂载 → 卸载（含驱动反注册 + 关池 hook 的卸载路径），
 * 经 {@link LeakDetector} 弱引用队列断言每轮卸载后 ClassLoader 可回收、
 * LeakRiskReport 为 clean（NO_RISK），多轮循环后弱引用队列全部回收（无泄漏）。
 */
@DisplayName("业务灵元多轮挂载/卸载可证 GC（LeakRiskReport clean）")
class BusinessLingGcCollectabilityTest {

    private static final String LING_ID = "biz-ling";
    private static final String VERSION = "1.0.0";
    private static final String SERVICE_CLASS_NAME = "sample.ling.PrivateDbService";
    private static final String REQUIRED_PERMISSION = "biz:execute";

    @Test
    @DisplayName("多轮挂载/卸载：每轮 LeakRiskReport clean 且 ClassLoader 可回收，弱引用队列全部回收")
    void repeatedMountUnmountCyclesRemainCleanAndCollectible() throws Throwable {
        TestRuntime runtime = createTestRuntime();
        try {
            int cycles = 5;
            for (int i = 0; i < cycles; i++) {
                CycleResult cycle = runCycle(runtime, "round-" + i);

                // 每轮卸载后：ClassLoader 已 close、LeakRiskReport clean（NO_RISK）、可回收
                assertTrue(cycle.classLoaderClosed, "round-" + i + " classloader should be closed");
                assertEquals(LeakRiskLevel.NO_RISK, cycle.overallRiskLevel,
                        "round-" + i + " leak precheck should be clean");
                assertClassLoaderCollected(cycle.classLoaderCollected, i);
            }

            // 驱动反注册 + 关池 hook：每轮卸载都执行清理（累计 = 轮数）
            assertEquals(cycles, runtime.jvmHook.cleanupCount.get(),
                    "jvm cleanup hook (driver deregistration + pool close) should run once per cycle");
            // LeakDetector 弱引用队列：全部可回收（无泄漏）
            runtime.detector.assertAllCollected("all tracked classloaders should be GC-collected");
        } finally {
            runtime.shutdown();
        }
    }

    private CycleResult runCycle(TestRuntime runtime, String input) throws Throwable {
        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(VERSION);
        definition.setMainClass(SERVICE_CLASS_NAME);

        runtime.lifecycleEngine.deploy(definition, runtime.classesDir.toFile(), true, Collections.emptyMap());

        CloseAwareClassLoader targetClassLoader = runtime.loaderHolder.get();
        assertNotNull(targetClassLoader);
        WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(targetClassLoader);

        // 卸载：预检报告 + JVM hook 清理（驱动反注册 + 关池路径）
        LingUninstallResult uninstallResult = runtime.lifecycleEngine.undeployWithReport(LING_ID);

        boolean classLoaderClosed = runtime.classLoaderClosed.get();
        // 释放引用并等待 GC 回收
        targetClassLoader = null;
        runtime.loaderHolder.set(null);
        awaitCollection(classLoaderRef);

        return new CycleResult(classLoaderClosed,
                classLoaderRef.get() == null,
                uninstallResult.getOverallRiskLevel());
    }

    private TestRuntime createTestRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("ling-gc-collectability");
        Path sourceDir = workspace.resolve("src");
        Path classesDir = workspace.resolve("classes");
        compileServiceClass(sourceDir, classesDir);

        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingRepository repository = new DefaultLingRepository();
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed(isNull(), eq(REQUIRED_PERMISSION), eq(AccessType.EXECUTE))).thenReturn(true);

        // 自定义 LeakDetector：checkBefore 返回 clean，detectLeak 记录弱引用队列
        CollectibleLeakDetector detector = new CollectibleLeakDetector();
        // JVM hook：模拟驱动反注册 + 关池（模式 2 私有库卸载路径）
        CountingJvmHook jvmHook = new CountingJvmHook();

        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);
        LingUnloadCoordinator unloadCoordinator =
                new LingUnloadCoordinator(pipelineEngine, Collections.emptyList(),
                        Collections.singletonList(jvmHook), null, detector);

        AtomicReference<CloseAwareClassLoader> loaderHolder = new AtomicReference<>();
        AtomicBoolean classLoaderClosed = new AtomicBoolean(false);
        LingLoaderFactory loaderFactory = (lingId, sourceFile, parent) -> {
            classLoaderClosed.set(false);
            CloseAwareClassLoader classLoader;
            try {
                classLoader = new CloseAwareClassLoader(
                        new URL[]{classesDir.toUri().toURL()}, parent, classLoaderClosed);
            } catch (java.net.MalformedURLException e) {
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
                        .forceCleanupDelaySeconds(0)
                        .build())
                .build();
        List<LingSecurityVerifier> verifiers = Collections.singletonList(
                new DangerousApiVerifier(false, Collections.emptyList(), null));

        DefaultLingLifecycleEngine lifecycleEngine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(permissionService)
                .lingLoaderFactory(loaderFactory)
                .verifiers(verifiers)
                .eventBus(eventBus)
                .lingFrameConfig(config)
                .lingRepository(repository)
                .lingServiceRegistry(mock(LingServiceRegistry.class))
                .pipelineEngine(pipelineEngine)
                .lingResourceManager(null)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        return new TestRuntime(workspace, classesDir, repository, lifecycleEngine,
                runtimeCoordinator, loaderHolder, classLoaderClosed, jvmHook, detector);
    }

    private void awaitCollection(WeakReference<ClassLoader> reference) throws InterruptedException {
        for (int i = 0; i < 20 && reference.get() != null; i++) {
            System.gc();
            System.runFinalization();
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    private static void assertClassLoaderCollected(boolean collected, int round) {
        assertTrue(collected, "round-" + round + " ClassLoader 应在 undeploy 后被 GC 回收，存在泄漏");
    }

    private void compileServiceClass(Path sourceDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is unavailable");
        }
        Path packageDir = sourceDir.resolve("sample/ling");
        Files.createDirectories(packageDir);
        Files.createDirectories(classesDir);

        Path sourceFile = packageDir.resolve("PrivateDbService.java");
        String source = ""
                + "package sample.ling;\n"
                + "public class PrivateDbService {\n"
                + "    public String execute(String input) {\n"
                + "        return \"private:\" + input;\n"
                + "    }\n"
                + "}\n";
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classesDir.toFile()));
            boolean success = compiler.getTask(
                    null, fileManager, null, null, null,
                    fileManager.getJavaFileObjects(sourceFile.toFile())).call();
            if (!success) {
                throw new IllegalStateException("Failed to compile test ling class");
            }
        }
    }

    /** LeakDetector 实现：checkBefore 恒返回 NO_RISK，detectLeak 记录弱引用队列供回收断言 */
    private static final class CollectibleLeakDetector implements LeakDetector {
        private final List<WeakReference<ClassLoader>> tracked = new ArrayList<>();

        @Override
        public LeakRiskReport checkBefore(String lingId, String version, ClassLoader classLoader) {
            return LeakRiskReport.noRisk(lingId, version,
                    "business ling classloader tracked for GC collectability", null, getClass().getName());
        }

        @Override
        public void detectLeak(String lingId, String version, ClassLoader classLoader) {
            tracked.add(new WeakReference<>(classLoader));
        }

        private void assertAllCollected(String message) throws InterruptedException {
            for (int i = 0; i < 20; i++) {
                System.gc();
                System.runFinalization();
                boolean anyAlive = tracked.stream().anyMatch(ref -> ref.get() != null);
                if (!anyAlive) {
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertTrue(tracked.stream().noneMatch(ref -> ref.get() != null), message);
        }
    }

    /** JVM hook：模拟驱动反注册 + 关池（模式 2 私有库卸载路径的清理动作） */
    private static final class CountingJvmHook implements LingUnloadHook {
        private final AtomicInteger cleanupCount = new AtomicInteger();

        @Override
        public void cleanup(String lingId, ClassLoader classLoader) {
            cleanupCount.incrementAndGet();
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
                this.bean = classLoader.loadClass(mainClassName).getDeclaredConstructor().newInstance();
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
        public <T> T getBean(Class<T> type) {
            return bean != null && type.isInstance(bean) ? type.cast(bean) : null;
        }

        @Override
        public Object getBean(String beanName) {
            return bean;
        }

        @Override
        public String[] getBeanNames() {
            return bean == null ? new String[0] : new String[]{mainClassName};
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
        private final DefaultLingLifecycleEngine lifecycleEngine;
        private final RuntimeCoordinator runtimeCoordinator;
        private final AtomicReference<CloseAwareClassLoader> loaderHolder;
        private final AtomicBoolean classLoaderClosed;
        private final CountingJvmHook jvmHook;
        private final CollectibleLeakDetector detector;

        private TestRuntime(Path workspace, Path classesDir, DefaultLingRepository repository,
                DefaultLingLifecycleEngine lifecycleEngine, RuntimeCoordinator runtimeCoordinator,
                AtomicReference<CloseAwareClassLoader> loaderHolder, AtomicBoolean classLoaderClosed,
                CountingJvmHook jvmHook, CollectibleLeakDetector detector) {
            this.workspace = workspace;
            this.classesDir = classesDir;
            this.repository = repository;
            this.lifecycleEngine = lifecycleEngine;
            this.runtimeCoordinator = runtimeCoordinator;
            this.loaderHolder = loaderHolder;
            this.classLoaderClosed = classLoaderClosed;
            this.jvmHook = jvmHook;
            this.detector = detector;
        }

        private void shutdown() throws IOException {
            runtimeCoordinator.stop();
            if (Files.exists(workspace)) {
                try (Stream<Path> stream = Files.walk(workspace)) {
                    stream.sorted((l, r) -> r.getNameCount() - l.getNameCount())
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
    }

    private static final class CycleResult {
        private final boolean classLoaderClosed;
        private final boolean classLoaderCollected;
        private final LeakRiskLevel overallRiskLevel;

        private CycleResult(boolean classLoaderClosed, boolean classLoaderCollected,
                LeakRiskLevel overallRiskLevel) {
            this.classLoaderClosed = classLoaderClosed;
            this.classLoaderCollected = classLoaderCollected;
            this.overallRiskLevel = overallRiskLevel;
        }
    }
}
