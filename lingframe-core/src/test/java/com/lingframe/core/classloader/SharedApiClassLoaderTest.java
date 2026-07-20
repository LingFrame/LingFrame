package com.lingframe.core.classloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SharedApiClassLoader 测试")
class SharedApiClassLoaderTest {

    @AfterEach
    void tearDown() {
        SharedApiClassLoader.resetInstance();
    }

    @Test
    @DisplayName("getInstance 返回单例")
    void shouldReturnSingleton() {
        SharedApiClassLoader cl1 = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        SharedApiClassLoader cl2 = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        assertSame(cl1, cl2);
    }

    @Test
    @DisplayName("getInstanceOrNull 未初始化时返回 null")
    void shouldReturnNullWhenNotInitialized() {
        // resetInstance 确保干净状态
        SharedApiClassLoader.resetInstance();
        assertNull(SharedApiClassLoader.getInstanceOrNull());
    }

    @Test
    @DisplayName("addApiClassesDir 添加有效目录")
    void shouldAddApiClassesDir(@TempDir File tempDir) {
        SharedApiClassLoader cl = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();

        assertDoesNotThrow(() -> cl.addApiClassesDir(classesDir));
        assertEquals(1, cl.getLoadedJarCount());
    }

    @Test
    @DisplayName("addApiClassesDir null 目录应抛出异常")
    void shouldThrowOnNullClassesDir() {
        SharedApiClassLoader cl = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        assertThrows(Exception.class, () -> cl.addApiClassesDir(null));
    }

    @Test
    @DisplayName("addApiJar 不存在的文件应抛出异常")
    void shouldThrowOnNonExistentJar() {
        SharedApiClassLoader cl = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        assertThrows(Exception.class, () -> cl.addApiJar(new File("nonexistent.jar")));
    }

    @Test
    @DisplayName("freezeBoundary 后不能再添加 API")
    void shouldBlockAddAfterFreeze(@TempDir File tempDir) {
        SharedApiClassLoader cl = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        SharedApiClassLoader.freezeBoundary();
        assertTrue(SharedApiClassLoader.isBoundaryFrozen());

        File classesDir = new File(tempDir, "classes");
        classesDir.mkdirs();
        assertThrows(IllegalStateException.class, () -> cl.addApiClassesDir(classesDir));
    }

    @Test
    @DisplayName("resetInstance 后可重新初始化")
    void shouldResetInstance() {
        SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        SharedApiClassLoader.freezeBoundary();

        SharedApiClassLoader.resetInstance();
        assertFalse(SharedApiClassLoader.isBoundaryFrozen());
        assertNull(SharedApiClassLoader.getInstanceOrNull());
    }

    @Test
    @DisplayName("isSharedClass 未加载类返回 false")
    void shouldReturnFalseForUnloadedClass() {
        SharedApiClassLoader cl = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        assertFalse(cl.isSharedClass("com.example.NonExistent"));
    }

    @Test
    @DisplayName("toString 包含关键信息")
    void shouldReturnToString() {
        SharedApiClassLoader cl = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        String str = cl.toString();
        assertTrue(str.contains("SharedApiClassLoader"));
    }

    @Test
    @DisplayName("close 后 closed 标记生效")
    void shouldCloseSafely() throws IOException {
        SharedApiClassLoader cl = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
        assertDoesNotThrow(() -> cl.close());
        // 重复 close 不报错
        assertDoesNotThrow(() -> cl.close());
    }
}
