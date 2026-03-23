package com.lingframe.core.classloader;

import com.lingframe.core.exception.ClassLoaderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LingClassLoader 测试")
class LingClassLoaderTest {

    @AfterEach
    void tearDown() {
        LingClassLoader.resetSharedApiBoundary();
    }

    @Nested
    @DisplayName("类加载隔离")
    class IsolationTests {

        @Test
        @DisplayName("不同灵元之间应隔离缺失类加载")
        void shouldThrowClassNotFoundForMissingClassInIsolatedLoader() throws Exception {
            try (LingClassLoader first = new LingClassLoader("ling-1", new URL[0], ClassLoader.getSystemClassLoader());
                    LingClassLoader second = new LingClassLoader("ling-2", new URL[0], ClassLoader.getSystemClassLoader())) {
                assertThrows(ClassNotFoundException.class, () -> first.loadClass("com.example.MissingClass"));
                assertThrows(ClassNotFoundException.class, () -> second.loadClass("com.example.MissingClass"));
            }
        }
    }

    @Nested
    @DisplayName("生命周期行为")
    class LifecycleTests {

        @Test
        @DisplayName("关闭后不应再允许加载类或资源")
        void shouldRejectLoadingAfterClose() throws Exception {
            LingClassLoader classLoader = new LingClassLoader("ling-closed", new URL[0], ClassLoader.getSystemClassLoader());
            classLoader.close();

            assertTrue(classLoader.isClosed());
            assertThrows(ClassLoaderException.class, () -> classLoader.loadClass("java.lang.String"));
            assertNull(classLoader.getResource("any/resource"));
        }
    }

    @Nested
    @DisplayName("共享边界管理")
    class SharedBoundaryTests {

        @Test
        @DisplayName("冻结共享边界后不应再允许追加共享包前缀")
        void shouldRejectSharedBoundaryMutationAfterFreeze() {
            LingClassLoader.freezeSharedApiBoundary();

            assertThrows(IllegalStateException.class,
                    () -> LingClassLoader.addSharedApiPackages(Collections.singletonList("demo.shared.")));
        }
    }
}
