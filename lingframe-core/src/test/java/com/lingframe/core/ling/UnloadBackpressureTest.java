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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 业务灵元卸载依赖反压测试（对齐代码事实）。
 * <p>
 * 反压机制：{@link LingInstance#beginInvocation} 递增引用计数（在途请求），
 * {@link LingInstance#exit} 递减；卸载 drain 在 {@code forceDrainOnTimeout=false}
 * 时若仍有活跃请求则抛 {@link IllegalStateException}（拒绝卸载，不静默打断业务），
 * 引用释放（exit）后可正常卸载。
 */
@DisplayName("业务灵元卸载依赖反压")
class UnloadBackpressureTest {

    private static final String LING_ID = "biz-ling";
    private static final String VERSION = "1.0.0";
    private static final String SERVICE_CLASS_NAME = "sample.ling.PrivateDbService";
    private static final String REQUIRED_PERMISSION = "biz:execute";

    @Test
    @DisplayName("引用存在（在途请求）时卸载被拒绝：forceDrainOnTimeout=false 抛 IllegalStateException")
    void unloadRejectedWhileInFlightReferenceHeld() throws Throwable {
        TestRuntime runtime = createTestRuntime();
        try {
            LingInstance instance = deployAndHoldInvocation(runtime);

            // 引用存在：卸载 drain 超时后 wait-only 策略拒绝卸载（不静默打断在途请求）
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> runtime.lifecycleEngine.undeploy(LING_ID));
            assertTrue(ex.getMessage().contains("still busy")
                            || ex.getMessage().contains("Drain timeout"),
                    "wait-only drain should report busy instance: " + ex.getMessage());

            // 引用释放后可正常卸载
            instance.exit();
            runtime.lifecycleEngine.undeploy(LING_ID);
            assertTrue(runtime.repository.getRuntime(LING_ID) == null
                            || !runtime.repository.hasRuntime(LING_ID),
                    "after reference released, undeploy should complete");
        } finally {
            runtime.shutdown();
        }
    }

    @Test
    @DisplayName("无在途引用时卸载正常完成（反压不误伤空闲灵元）")
    void unloadSucceedsWhenNoInflightReference() throws Throwable {
        TestRuntime runtime = createTestRuntime();
        try {
            LingDefinition definition = new LingDefinition();
            definition.setId(LING_ID);
            definition.setVersion(VERSION);
            definition.setMainClass(SERVICE_CLASS_NAME);
            runtime.lifecycleEngine.deploy(definition, runtime.classesDir.toFile(), true, Collections.emptyMap());

            // 无引用：立即卸载成功
            runtime.lifecycleEngine.undeploy(LING_ID);
            assertTrue(runtime.repository.getRuntime(LING_ID) == null
                            || !runtime.repository.hasRuntime(LING_ID),
                    "idle ling should undeploy cleanly");
        } finally {
            runtime.shutdown();
        }
    }

    /**
     * 部署灵元并持有一次在途引用（beginInvocation 递增引用计数）。
     */
    private LingInstance deployAndHoldInvocation(TestRuntime runtime) throws Throwable {
        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(VERSION);
        definition.setMainClass(SERVICE_CLASS_NAME);
        runtime.lifecycleEngine.deploy(definition, runtime.classesDir.toFile(), true, Collections.emptyMap());

        LingRuntime lingRuntime = runtime.repository.getRuntime(LING_ID);
        LingInstance instance = lingRuntime.getInstancePool().getDefault();
        ActiveInvocationSnapshot snapshot = new ActiveInvocationSnapshot(
                "trace-backpressure", LING_ID + ":" + SERVICE_CLASS_NAME, "execute",
                null, null, VERSION, System.currentTimeMillis(), Thread.currentThread().getId(),
                Thread.currentThread().getName());
        long invocationId = instance.beginInvocation(snapshot);
        assertTrue(invocationId >= 0, "beginInvocation should hold an in-flight reference");
        return instance;
    }

    private TestRuntime createTestRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("ling-unload-backpressure");
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
                        // wait-only：drain 超时后拒绝卸载（不静默打断在途请求）
                        .forceDrainOnTimeout(false)
                        // 短宽限期让 drain 快速超时，测试不悬挂
                        .forceCleanupDelaySeconds(1)
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

        return new TestRuntime(workspace, classesDir, repository, lifecycleEngine, runtimeCoordinator);
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

        private TestRuntime(Path workspace, Path classesDir, DefaultLingRepository repository,
                DefaultLingLifecycleEngine lifecycleEngine, RuntimeCoordinator runtimeCoordinator) {
            this.workspace = workspace;
            this.classesDir = classesDir;
            this.repository = repository;
            this.lifecycleEngine = lifecycleEngine;
            this.runtimeCoordinator = runtimeCoordinator;
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
