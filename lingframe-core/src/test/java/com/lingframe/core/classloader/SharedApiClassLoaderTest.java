package com.lingframe.core.classloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

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
}
