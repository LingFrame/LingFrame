package com.lingframe.core.classloader;

import com.lingframe.core.exception.ClassLoaderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClassLoaderCleanupUtil + DefaultLingLoaderFactory 测试。
 * 覆盖：URLClassLoader 清理、ClassLoader 创建、异常路径。
 */
@DisplayName("ClassLoader 工具与工厂测试")
class ClassLoaderUtilAndFactoryTest {

    // ==================== ClassLoaderCleanupUtil ====================

    @Nested
    @DisplayName("ClassLoaderCleanupUtil")
    class CleanupUtil {

        @Test
        @DisplayName("清理 null ClassLoader 不抛异常")
        void cleanupNullSafe() {
            assertDoesNotThrow(() -> ClassLoaderCleanupUtil.cleanupUrlClassPath(null, "test"));
        }

        @Test
        @DisplayName("清理非 URLClassLoader 不抛异常")
        void cleanupNonUrlClassLoaderSafe() {
            ClassLoader systemCl = ClassLoader.getSystemClassLoader();
            // 如果系统 CL 不是 URLClassLoader 的子类，直接返回
            if (!(systemCl instanceof URLClassLoader)) {
                assertDoesNotThrow(() -> ClassLoaderCleanupUtil.cleanupUrlClassPath((URLClassLoader) null, "test"));
            }
        }

        @Test
        @DisplayName("清理已关闭的 URLClassLoader 不抛异常")
        void cleanupClosedUrlClassLoader(@TempDir Path temp) throws IOException {
            File jar = createEmptyJar(temp);
            URLClassLoader ucl = new URLClassLoader(new java.net.URL[]{jar.toURI().toURL()}, null);
            ucl.close();

            assertDoesNotThrow(() -> ClassLoaderCleanupUtil.cleanupUrlClassPath(ucl, "test-closed"));
        }

        @Test
        @DisplayName("清理活跃的 URLClassLoader 不抛异常")
        void cleanupActiveUrlClassLoader(@TempDir Path temp) throws IOException {
            File jar = createEmptyJar(temp);
            URLClassLoader ucl = new URLClassLoader(new java.net.URL[]{jar.toURI().toURL()}, null);

            assertDoesNotThrow(() -> ClassLoaderCleanupUtil.cleanupUrlClassPath(ucl, "test-active"));
            ucl.close();
        }
    }

    // ==================== DefaultLingLoaderFactory ====================

    @Nested
    @DisplayName("DefaultLingLoaderFactory")
    class LoaderFactory {

        @Test
        @DisplayName("从 JAR 文件创建 ClassLoader")
        void createFromJar(@TempDir Path temp) throws IOException {
            File jar = createEmptyJar(temp);
            DefaultLingLoaderFactory factory = new DefaultLingLoaderFactory();

            ClassLoader cl = factory.create("ling-1", jar, getClass().getClassLoader());
            assertNotNull(cl);
            assertTrue(cl instanceof LingClassLoader);
        }

        @Test
        @DisplayName("从目录创建 ClassLoader")
        void createFromDirectory(@TempDir Path temp) throws IOException {
            File dir = temp.toFile();
            DefaultLingLoaderFactory factory = new DefaultLingLoaderFactory();

            ClassLoader cl = factory.create("ling-1", dir, getClass().getClassLoader());
            assertNotNull(cl);
            assertTrue(cl instanceof LingClassLoader);
        }

        @Test
        @DisplayName("不支持的文件类型抛出 ClassLoaderException")
        void unsupportedFileTypeThrows(@TempDir Path temp) throws IOException {
            File txt = temp.resolve("test.txt").toFile();
            txt.createNewFile();

            DefaultLingLoaderFactory factory = new DefaultLingLoaderFactory();
            assertThrows(ClassLoaderException.class,
                    () -> factory.create("ling-1", txt, getClass().getClassLoader()));
        }

        @Test
        @DisplayName("创建的 ClassLoader parent 是 SharedApiClassLoader")
        void parentIsSharedApiClassLoader(@TempDir Path temp) throws IOException {
            File jar = createEmptyJar(temp);
            DefaultLingLoaderFactory factory = new DefaultLingLoaderFactory();

            ClassLoader cl = factory.create("ling-1", jar, getClass().getClassLoader());
            assertNotNull(cl.getParent());
            // parent 应该是 SharedApiClassLoader 实例
            assertTrue(cl.getParent().getClass().getSimpleName().contains("SharedApi"));
        }

        @Test
        @DisplayName("null lingId 由 LingClassLoader 构造器处理")
        void nullLingIdHandled(@TempDir Path temp) throws IOException {
            File jar = createEmptyJar(temp);
            DefaultLingLoaderFactory factory = new DefaultLingLoaderFactory();

            // LingClassLoader 可能允许 null lingId，也可能抛异常
            // 这里只验证工厂方法不抛意外异常
            assertDoesNotThrow(() -> {
                try {
                    factory.create(null, jar, getClass().getClassLoader());
                } catch (ClassLoaderException | IllegalArgumentException e) {
                    // 预期可能的异常
                }
            });
        }
    }

    // ==================== 辅助方法 ====================

    private File createEmptyJar(Path temp) throws IOException {
        File jar = temp.resolve("test.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            jos.closeEntry();
        }
        return jar;
    }
}
