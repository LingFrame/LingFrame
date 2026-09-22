package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * finally 双端擦除遗漏探测测试（契约自检专项）。
 * <p>
 * 场景：worker 线程 finally 中遗漏 {@code restoreSnapshot()}（模拟编码遗漏），
 * 灵元 ClassLoader 定义的 Connection 代理残留于线程 {@link LingTransactionContext}
 * ThreadLocal——残留链 = 线程 → ThreadLocal → 连接栈 → 代理实例 → 代理类 → 灵元
 * ClassLoader，卸载后该链阻止 ClassLoader 回收。
 * <p>
 * 断言：
 * <ul>
 *   <li>有残留（遗漏）→ ClassLoader 不可回收（泄漏可被 {@code LeakDetector}
 *       弱引用探测检出，编码遗漏可观测）；</li>
 *   <li>清理（pop + cleanIfEmpty）→ ClassLoader 可回收（正确擦除 clean）。</li>
 * </ul>
 */
@DisplayName("finally 双端擦除遗漏探测（ThreadLocal 残留可观测）")
class FinallyEraseOmissionLeakDetectorTest {

    private static final String LING_ID = "biz-ling";
    private static final String VERSION = "1.0.0";
    private static final String SERVICE_CLASS_NAME = "sample.ling.PrivateDbService";
    private static final String REQUIRED_PERMISSION = "biz:execute";

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    @Test
    @DisplayName("worker finally 遗漏擦除 → ThreadLocal 残留连接使 ClassLoader 不可回收（泄漏可观测）")
    void residualThreadLocalConnectionKeepsClassLoaderAlive() throws Throwable {
        TestRuntime runtime = createTestRuntime();
        try {
            ClassLoader lingClassLoader = deployLing(runtime);
            assertNotNull(lingClassLoader);
            WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(lingClassLoader);

            // 模拟编码遗漏：灵元 ClassLoader 定义的 Connection 代理压入线程 ThreadLocal，不清理
            Connection residual = newLingConnectionProxy(lingClassLoader);
            LingTransactionContext.pushConnection("default", residual);
            residual = null;   // 释放局部强引用，仅留 ThreadLocal 残留链
            // 释放测试侧全部强引用（局部变量 + loaderHolder），确保不可回收唯一归因于 ThreadLocal 残留
            lingClassLoader = null;
            runtime.loaderHolder.set(null);

            // 卸载灵元
            runtime.lifecycleEngine.undeploy(LING_ID);

            // 残留链阻止回收：多次 GC 后 ClassLoader 仍存活（LeakDetector 弱引用可检出）
            awaitCollectionAttempt(classLoaderRef);
            assertFalse(classLoaderRef.get() == null,
                    "ThreadLocal residual should keep ling ClassLoader alive (leak observable)");

            // 清理残留后即可回收（正确擦除路径）
            LingTransactionContext.clear();
            assertClassLoaderCollected(classLoaderRef, "after cleanup, ClassLoader should be collectible");
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    @DisplayName("正确擦除（pop + cleanIfEmpty）→ 无 ThreadLocal 残留，卸载后 ClassLoader 可回收（clean）")
    void cleanedThreadLocalAllowsClassLoaderCollection() throws Throwable {
        TestRuntime runtime = createTestRuntime();
        try {
            ClassLoader lingClassLoader = deployLing(runtime);
            WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(lingClassLoader);

            // 正确擦除路径：压入后 finally 清理（pop + cleanIfEmpty）
            Connection residual = newLingConnectionProxy(lingClassLoader);
            LingTransactionContext.pushConnection("default", residual);
            LingTransactionContext.popConnection();
            LingTransactionContext.cleanIfEmpty();
            residual = null;
            // 释放测试侧全部强引用（局部变量 + loaderHolder）
            lingClassLoader = null;
            runtime.loaderHolder.set(null);

            runtime.lifecycleEngine.undeploy(LING_ID);

            assertClassLoaderCollected(classLoaderRef, "correctly erased ThreadLocal should allow collection");
        } finally {
            runtime.shutdown();
        }
    }

    /**
     * 用灵元 ClassLoader 创建 Connection 动态代理（代理类定义在灵元 ClassLoader 上，
     * 模拟「灵元加载的连接实例」——穿透连接的类归属灵元 ClassLoader）。
     */
    private Connection newLingConnectionProxy(ClassLoader lingClassLoader) {
        return (Connection) Proxy.newProxyInstance(
                lingClassLoader,
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> null);
    }

    private ClassLoader deployLing(TestRuntime runtime) throws Throwable {
        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(VERSION);
        definition.setMainClass(SERVICE_CLASS_NAME);
        runtime.lifecycleEngine.deploy(definition, runtime.classesDir.toFile(), true, Collections.emptyMap());
        return runtime.loaderHolder.get();
    }

    private TestRuntime createTestRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("ling-finally-erase-omission");
        Path sourceDir = workspace.resolve("src");
        Path classesDir = workspace.resolve("classes");
        compileServiceClass(sourceDir, classesDir);

        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingRepository repository = new DefaultLingRepository();
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed(isNull(), eq(REQUIRED_PERMISSION), eq(AccessType.EXECUTE))).thenReturn(true);

        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);
        LingUnloadCoordinator unloadCoordinator =
                new LingUnloadCoordinator(pipelineEngine, Collections.emptyList(),
                        Collections.emptyList(), null, null);

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
                runtimeCoordinator, loaderHolder);
    }

    private void awaitCollectionAttempt(WeakReference<ClassLoader> reference) throws InterruptedException {
        // 尝试回收：给 GC 机会（有残留时应当回收失败）
        for (int i = 0; i < 10; i++) {
            System.gc();
            System.runFinalization();
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    private static void assertClassLoaderCollected(WeakReference<ClassLoader> reference, String message)
            throws InterruptedException {
        for (int i = 0; i < 20 && reference.get() != null; i++) {
            System.gc();
            System.runFinalization();
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(reference.get() == null, message);
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

        private TestRuntime(Path workspace, Path classesDir, DefaultLingRepository repository,
                DefaultLingLifecycleEngine lifecycleEngine, RuntimeCoordinator runtimeCoordinator,
                AtomicReference<CloseAwareClassLoader> loaderHolder) {
            this.workspace = workspace;
            this.classesDir = classesDir;
            this.repository = repository;
            this.lifecycleEngine = lifecycleEngine;
            this.runtimeCoordinator = runtimeCoordinator;
            this.loaderHolder = loaderHolder;
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
}
