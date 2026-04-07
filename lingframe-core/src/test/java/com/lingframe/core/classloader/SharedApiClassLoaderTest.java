package com.lingframe.core.classloader;

import com.lingframe.core.exception.ClassLoaderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("SharedApiClassLoader 测试")
class SharedApiClassLoaderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        LingClassLoader.resetSharedApiBoundary();
        SharedApiClassLoader.resetInstance();
    }

    @Nested
    @DisplayName("单例行为")
    class SingletonTests {

        @Test
        @DisplayName("重复获取应返回同一个实例")
        void shouldReturnSingletonInstanceAcrossCalls() {
            SharedApiClassLoader first = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            SharedApiClassLoader second = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());

            assertSame(first, second);
        }
    }

    @Nested
    @DisplayName("共享目录加载")
    class LoadingTests {

        @Test
        @DisplayName("添加共享类目录后应更新已加载计数")
        void shouldIncreaseLoadedCountWhenAddingClassesDirectory() {
            SharedApiClassLoader loader = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            File classesDir = tempDir.toFile();

            loader.addApiClassesDir(classesDir);

            assertEquals(1, loader.getLoadedJarCount());
        }

        @Test
        @DisplayName("同名共享类来自不同 classes 目录时应确定性失败")
        void shouldFailFastWhenClassesDirectoryConflictsWithExistingSharedClass() throws Exception {
            SharedApiClassLoader loader = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            Path firstDir = compileSharedClass(tempDir.resolve("first-classes"), "sample.shared.Contract");
            Path secondDir = compileSharedClass(tempDir.resolve("second-classes"), "sample.shared.Contract");

            loader.addApiClassesDir(firstDir.toFile());

            assertThrows(ClassLoaderException.class, () -> loader.addApiClassesDir(secondDir.toFile()));
        }

        @Test
        @DisplayName("同名共享类来自不同 JAR 时应确定性失败")
        void shouldFailFastWhenJarConflictsWithExistingSharedClass() throws Exception {
            SharedApiClassLoader loader = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            Path firstDir = compileSharedClass(tempDir.resolve("jar-first-classes"), "sample.shared.Contract");
            Path secondDir = compileSharedClass(tempDir.resolve("jar-second-classes"), "sample.shared.Contract");
            File firstJar = createJar(firstDir, tempDir.resolve("first.jar"));
            File secondJar = createJar(secondDir, tempDir.resolve("second.jar"));

            loader.addApiJar(firstJar);

            assertThrows(ClassLoaderException.class, () -> loader.addApiJar(secondJar));
        }
    }

    @Nested
    @DisplayName("边界冻结")
    class BoundaryFreezeTests {

        @Test
        @DisplayName("冻结边界后不应再允许添加共享目录")
        void shouldRejectNewEntriesAfterBoundaryIsFrozen() {
            SharedApiClassLoader loader = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            SharedApiClassLoader.freezeBoundary();

            assertThrows(IllegalStateException.class, () -> loader.addApiClassesDir(tempDir.toFile()));
        }
    }

    private Path compileSharedClass(Path classesDir, String className) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is unavailable");
        }

        Path sourceDir = Files.createDirectories(tempDir.resolve(classesDir.getFileName() + "-src"));
        String relativeName = className.replace('.', '/');
        Path sourceFile = sourceDir.resolve(relativeName + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classesDir);

        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        String packageName = className.substring(0, className.lastIndexOf('.'));
        String source = ""
                + "package " + packageName + ";\n"
                + "public class " + simpleName + " {\n"
                + "    public String name() { return \"" + simpleName + "\"; }\n"
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
                throw new IllegalStateException("Failed to compile shared API test class");
            }
        }
        return classesDir;
    }

    private File createJar(Path classesDir, Path jarPath) throws IOException {
        try (JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            Files.walk(classesDir)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String entryName = classesDir.relativize(path).toString().replace('\\', '/');
                        try {
                            jarOutputStream.putNextEntry(new JarEntry(entryName));
                            jarOutputStream.write(Files.readAllBytes(path));
                            jarOutputStream.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
        return jarPath.toFile();
    }
}
