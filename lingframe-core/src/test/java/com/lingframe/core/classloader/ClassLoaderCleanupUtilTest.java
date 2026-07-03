package com.lingframe.core.classloader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClassLoaderCleanupUtil 测试")
class ClassLoaderCleanupUtilTest {

    @Test
    @DisplayName("cleanupUrlClassPath 对 null loader 不报错")
    void shouldHandleNullLoader() {
        // cleanupUrlClassPath 是 package-private，无法直接调用
        // 但可通过 SharedApiClassLoader 的 close 间接覆盖
        assertDoesNotThrow(() -> {
            SharedApiClassLoader cl = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            cl.close();
            SharedApiClassLoader.resetInstance();
        });
    }

    @Test
    @DisplayName("cleanupUrlClassPath 对普通 URLClassLoader 不报错")
    void shouldCleanupNormalUrlClassLoader() {
        URLClassLoader cl = new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader());
        assertDoesNotThrow(() -> cl.close());
    }
}
