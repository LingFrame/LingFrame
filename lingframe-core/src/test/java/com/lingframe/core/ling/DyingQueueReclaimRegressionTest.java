package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
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
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("濒死队列回收回归测试（B1：僵尸实例泄漏）")
class DyingQueueReclaimRegressionTest {

    private static final String LING_ID = "dying-reclaim-ling";
    private static final String SERVICE_CLASS_NAME = "sample.e2e.EchoService";

    @AfterEach
    void tearDown() throws Exception {
        if (currentRuntime != null) {
            try {
                currentRuntime.lifecycleEngine.undeploy(LING_ID);
            } catch (Exception ignored) {
            }
            deleteRecursively(currentRuntime.workspace);
            currentRuntime = null;
        }
    }

    private TestRuntime currentRuntime;

    @Test
    @DisplayName("反复部署替换默认实例后，被替换的实时实例应立即回收，濒死队列不累积")
    void replacedDefaultShouldBeReclaimedImmediately() throws Throwable {
        TestRuntime runtime = createRuntime();
        currentRuntime = runtime;

        // 连续部署 6 个版本（1.0.1 ~ 1.0.6），每次都顶替默认实例
        for (int i = 1; i <= 6; i++) {
            deployVersion(runtime, "1.0." + i);

            InstancePool pool = runtime.getPool();
            // 濒死队列必须为空：被替换的旧默认实例若已 idle，应立即被回收
            int dying = 0;
            int active = pool.getActiveInstances().size();
            for (LingInstance instance : pool.getAllInstances()) {
                if (instance.currentStatus() == InstanceStatus.STOPPING
                        || instance.currentStatus() == InstanceStatus.DEAD) {
                    dying++;
                }
            }
            String round = "round-" + i;
            assertEquals(1, active, round + ": active 应始终只有最新版本");
            assertEquals(0, dying, round + ": 濒死实例不应滞留");
        }
    }

@Test
    @DisplayName("替换时仍在途的实例应暂留濒死队列，待空闲后由下一次部署排空回收")
    void inFlightInstanceShouldBeReclaimedOnNextDeploy() throws Throwable {
        TestRuntime runtime = createRuntime();
        currentRuntime = runtime;

        deployVersion(runtime, "1.0.1");
        LingInstance v1 = runtime.getPool().getDefault();
        // 模拟 v1 有在途请求：替换时不应被立即回收，需保留到空闲
        long invocationId = v1.beginInvocation(new ActiveInvocationSnapshot(
                "trace-reclaim",
                LING_ID + ":sample.e2e.EchoService",
                "echo",
                "caller-a",
                "GET /echo",
                "1.0.1",
                1000L,
                1L,
                "worker-1"));

        deployVersion(runtime, "1.0.2");
        assertTrue(v1.isDying(), "在途实例替换后应进入濒死队列而非被立即回收");

        // v1 完成在途请求后即可被回收；此时再部署 v3，应对该池濒死队列统一排空回收 v1
        v1.completeInvocation(invocationId);
        deployVersion(runtime, "1.0.3");
        assertEquals(InstanceStatus.DEAD, v1.currentStatus(),
                "空闲后的濒死实例应在下一次部署排空时被 tearDown 回收");
    }

    @Test
    @DisplayName("被回收的旧默认实例应在脱离引用后（卸载后可证 GC）")
    void replacedDefaultShouldBeGcCollectible() throws Throwable {
        TestRuntime runtime = createRuntime();
        currentRuntime = runtime;

        List<WeakReference<LingInstance>> refs = new ArrayList<>();
        deployVersion(runtime, "1.0.1");
        for (int i = 2; i <= 5; i++) {
            LingInstance oldDefault = runtime.getPool().getDefault();
            deployVersion(runtime, "1.0." + i);
            refs.add(new WeakReference<>(oldDefault));
        }

        // 触发多次 GC，等待旧默认实例被回收（基于其类加载器与容器均已完成 tearDown）
        awaitCollection(refs);
        for (WeakReference<LingInstance> ref : refs) {
            assertNull(ref.get(), "被取代的默认实例应在立即回收后被 GC（存在濒死队列泄漏）");
        }
    }

    // ==================== 辅助方法 ====================

    private void deployVersion(TestRuntime runtime, String version) throws Throwable {
        LingDefinition definition = new LingDefinition();
        definition.setId(LING_ID);
        definition.setVersion(version);
        definition.setMainClass(SERVICE_CLASS_NAME);
        runtime.lifecycleEngine.deploy(definition, runtime.classesDir.toFile(), true, Collections.emptyMap());
    }

    private void awaitCollection(List<WeakReference<LingInstance>> refs) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            boolean allCollected = true;
            for (WeakReference<LingInstance> ref : refs) {
                if (ref.get() != null) {
                    allCollected = false;
                    break;
                }
            }
            if (allCollected) {
                return;
            }
            System.gc();
            System.runFinalization();
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    private TestRuntime createRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("dying-reclaim");
        Path sourceDir = workspace.resolve("src");
        Path classesDir = workspace.resolve("classes");
        compileServiceClass(sourceDir, classesDir);

        EventBus eventBus = new EventBus();
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();

        DefaultLingRepository repository = new DefaultLingRepository();
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed(null, "execute", AccessType.EXECUTE)).thenReturn(true);

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

        LingLoaderFactory loaderFactory = (lingId, sourceFile, parent) -> {
            try {
                return new URLClassLoader(new URL[]{classesDir.toUri().toURL()}, parent);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create test classloader", e);
            }
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
                .lingServiceRegistry(serviceRegistry)
                .pipelineEngine(pipelineEngine)
                .lingResourceManager(null)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .build());

        return new TestRuntime(workspace, classesDir, lifecycleEngine, repository, LING_ID);
    }

    private void compileServiceClass(Path sourceDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
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

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
    }

    private static final class TestRuntime {
        final Path workspace;
        final Path classesDir;
        final DefaultLingLifecycleEngine lifecycleEngine;
        final DefaultLingRepository repository;
        final String lingId;

        TestRuntime(Path workspace, Path classesDir, DefaultLingLifecycleEngine lifecycleEngine,
                DefaultLingRepository repository, String lingId) {
            this.workspace = workspace;
            this.classesDir = classesDir;
            this.lifecycleEngine = lifecycleEngine;
            this.repository = repository;
            this.lingId = lingId;
        }

        InstancePool getPool() {
            return repository.getRuntime(lingId).getInstancePool();
        }
    }

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
