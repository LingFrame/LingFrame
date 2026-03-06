package com.lingframe.core.classloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SharedApiClassLoader 灵元测试")
class SharedApiClassLoaderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        SharedApiClassLoader.resetInstance();
    }

    @Nested
    @DisplayName("单例模式")
    class SingletonTests {

        @Test
        @DisplayName("多次获取实例应为同一个对象")
        void testSingleton() {
            SharedApiClassLoader i1 = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            SharedApiClassLoader i2 = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            assertSame(i1, i2);
        }
    }

    @Nested
    @DisplayName("资源管理")
    class ResourceTests {

        @Test
        @DisplayName("添加 Classes 目录应增加加载计数")
        void testAddApiClassesDir() {
            SharedApiClassLoader loader = SharedApiClassLoader.getInstance(ClassLoader.getSystemClassLoader());
            File classesDir = tempDir.toFile();

            loader.addApiClassesDir(classesDir);
            assertEquals(1, loader.getLoadedJarCount());
        }
    }
}
