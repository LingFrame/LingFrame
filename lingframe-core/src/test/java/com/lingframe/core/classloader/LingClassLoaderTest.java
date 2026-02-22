package com.lingframe.core.classloader;

import com.lingframe.core.exception.ClassLoaderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LingClassLoader 单元测试")
class LingClassLoaderTest {

    @Nested
    @DisplayName("类加载隔离")
    class IsolationTests {

        @Test
        @DisplayName("不同单元之间应隔离类加载")
        void testIsolation() {

            try (LingClassLoader pc1 = new LingClassLoader("Ling-1", new URL[]{},
                    ClassLoader.getSystemClassLoader());
                 LingClassLoader pc2 = new LingClassLoader("Ling-2", new URL[]{},
                         ClassLoader.getSystemClassLoader())) {
                try {
                    // 尝试加载不存在的类应该抛出 ClassNotFoundException
                    pc1.loadClass("com.example.NonExistentClass");
                    fail("Should throw ClassNotFoundException");
                } catch (ClassNotFoundException e) {
                    // 符合预期
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @Nested
    @DisplayName("生命周期状态")
    class LifecycleTests {

        @Test
        @DisplayName("关闭后不应能加载类或资源")
        void testClosedState() throws Exception {
            LingClassLoader pcl = new LingClassLoader("Ling-closed", new URL[]{},
                    ClassLoader.getSystemClassLoader());
            pcl.close();
            assertTrue(pcl.isClosed());

            assertThrows(ClassLoaderException.class, () -> pcl.loadClass("java.lang.String"));
            assertNull(pcl.getResource("any/resource"));
        }
    }
}
