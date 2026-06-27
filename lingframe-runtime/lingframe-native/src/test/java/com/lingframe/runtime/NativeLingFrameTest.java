package com.lingframe.runtime;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.ServiceUnavailableException;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.ling.DefaultLingLifecycleEngine;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.classloader.LingClassLoader;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NativeLingFrame 测试")
class NativeLingFrameTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        NativeLingFrame.shutdown();
        LingClassLoader.resetSharedApiBoundary();
        LingFrameConfig.clear();
    }

    @Test
    @DisplayName("启动 Native runtime 后应冻结 Shared API 边界")
    void shouldFreezeSharedBoundaryDuringNativeBootstrap() {
        LingFrameConfig config = LingFrameConfig.builder()
                .lingHome(tempDir.toString())
                .lingRoots(Collections.emptyList())
                .preloadApiJars(Collections.emptyList())
                .build();
        LingFrameConfig.clear();

        try {
            NativeLingFrame.start(config);

            assertThrows(IllegalStateException.class,
                    () -> LingClassLoader.addSharedApiPackages(Collections.singletonList("demo.shared.")));
        } finally {
            NativeLingFrame.shutdown();
        }
    }

    @Test
    @DisplayName("关闭 Native runtime 后应允许重新进入 Shared API 引导期")
    void shouldResetSharedBoundaryAfterNativeShutdown() {
        LingFrameConfig config = LingFrameConfig.builder()
                .lingHome(tempDir.toString())
                .lingRoots(Collections.emptyList())
                .preloadApiJars(Collections.emptyList())
                .build();
        LingFrameConfig.clear();

        NativeLingFrame.start(config);
        NativeLingFrame.shutdown();

        assertDoesNotThrow(() -> LingClassLoader.addSharedApiPackages(Collections.singletonList("demo.shared.")));
    }

    @Test
    @DisplayName("shutdown 后可重新 start，状态完全重置")
    void shouldRestartAfterShutdown() {
        LingFrameConfig config = LingFrameConfig.builder()
                .lingHome(tempDir.toString())
                .lingRoots(Collections.emptyList())
                .preloadApiJars(Collections.emptyList())
                .build();
        LingFrameConfig.clear();

        // 第一次启动
        LingLifecycleEngine first = NativeLingFrame.start(config);
        assertNotNull(first);
        assertNotNull(NativeLingFrame.getHostContext());

        // 关闭
        NativeLingFrame.shutdown();
        assertThrows(ServiceUnavailableException.class, NativeLingFrame::getHostContext);

        // 重新启动
        LingFrameConfig config2 = LingFrameConfig.builder()
                .lingHome(tempDir.toString())
                .lingRoots(Collections.emptyList())
                .preloadApiJars(Collections.emptyList())
                .build();
        LingFrameConfig.clear();

        LingLifecycleEngine second = NativeLingFrame.start(config2);
        assertNotNull(second);
        assertNotNull(NativeLingFrame.getHostContext());

        NativeLingFrame.shutdown();
    }

    @Test
    @DisplayName("Native 路径应支持真实部署调用卸载后的 ClassLoader 回收")
    void shouldCollectClassLoaderAfterDeployInvokeAndUndeploy() throws Exception {
        Path lingHome = tempDir.resolve("ling-home");
        Files.createDirectories(lingHome);

        LingFrameConfig config = LingFrameConfig.builder()
                .autoScan(false)
                .lingHome(lingHome.toString())
                .lingRoots(Collections.emptyList())
                .preloadApiJars(Collections.emptyList())
                .build();
        LingFrameConfig.clear();

        LingLifecycleEngine lifecycleEngine = NativeLingFrame.start(config);
        Path sourceDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        compileNativeLing(sourceDir, classesDir);

        LingDefinition definition = new LingDefinition();
        definition.setId("native-e2e");
        definition.setVersion("1.0.0");
        definition.setMainClass("sample.nativee2e.EchoLing");

        lifecycleEngine.deploy(definition, classesDir.toFile(), true, Collections.emptyMap());

        LingRepository repository = getField(lifecycleEngine, "lingRepository", LingRepository.class);
        LingServiceRegistry serviceRegistry = getField(lifecycleEngine, "lingServiceRegistry", LingServiceRegistry.class);
        LingRuntime runtime = repository.getRuntime("native-e2e");
        assertNotNull(runtime);

        LingInstance instance = runtime.getInstancePool().getDefault();
        assertNotNull(instance);
        ClassLoader classLoader = instance.getClassLoader();
        assertNotNull(classLoader);
        assertTrue(classLoader instanceof LingClassLoader);

        WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(classLoader);
        InvocationPipelineEngine pipelineEngine = getField(lifecycleEngine, "pipelineEngine", InvocationPipelineEngine.class);
        assertEquals("echo:hello", invokeNativeService(pipelineEngine, serviceRegistry, "hello"));
        assertTrue(serviceRegistry.hasMethod("native-e2e:demo.echo", "echo", new String[] { "java.lang.String" }));

        lifecycleEngine.undeploy("native-e2e");

        assertFalse(repository.hasRuntime("native-e2e"));
        assertFalse(serviceRegistry.hasMethod("native-e2e:demo.echo", "echo", new String[] { "java.lang.String" }));
        assertTrue(((LingClassLoader) classLoader).isClosed());

        runtime = null;
        instance = null;
        classLoader = null;

        awaitCollection(classLoaderRef);
        assertNull(classLoaderRef.get());
    }

    private void compileNativeLing(Path sourceDir, Path classesDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is unavailable");
        }

        Path packageDir = sourceDir.resolve("sample/nativee2e");
        Files.createDirectories(packageDir);
        Files.createDirectories(classesDir);

        Path sourceFile = packageDir.resolve("EchoLing.java");
        String source = ""
                + "package sample.nativee2e;\n"
                + "import com.lingframe.api.annotation.LingService;\n"
                + "import com.lingframe.api.context.LingContext;\n"
                + "import com.lingframe.api.ling.Ling;\n"
                + "public class EchoLing implements Ling {\n"
                + "    @Override public void onStart(LingContext context) {}\n"
                + "    @Override public void onStop(LingContext context) {}\n"
                + "    @LingService(id = \"demo.echo\")\n"
                + "    public String echo(String input) { return \"echo:\" + input; }\n"
                + "}\n";
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(classesDir.toFile()));
            Iterable<String> options = java.util.Arrays.asList("-classpath", System.getProperty("java.class.path"));
            boolean success = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    options,
                    null,
                    fileManager.getJavaFileObjects(sourceFile.toFile()))
                    .call();
            if (!success) {
                throw new IllegalStateException("Failed to compile native test ling");
            }
        }
    }

    private void awaitCollection(WeakReference<ClassLoader> reference) throws InterruptedException {
        for (int i = 0; i < 20 && reference.get() != null; i++) {
            System.gc();
            System.runFinalization();
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    private Object invokeNativeService(InvocationPipelineEngine pipelineEngine,
                                       LingServiceRegistry serviceRegistry,
                                       String input) {
        InvocationContext context = InvocationContext.obtain();
        context.setServiceFQSID("native-e2e:demo.echo");
        context.setCallerLingId("lingcore-app");
        context.setTargetLingId("native-e2e");
        context.setMethodName("echo");
        context.setParameterTypeNames(new String[] { "java.lang.String" });
        context.setArgs(new Object[] { input });
        context.setRequiredPermission("native:test:invoke");
        context.setAccessType(AccessType.EXECUTE);

        try {
            return pipelineEngine.invoke(context);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to invoke native test service", throwable);
        } finally {
            context.recycle();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(Object target, String fieldName, Class<T> type) throws Exception {
        Field field = DefaultLingLifecycleEngine.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
