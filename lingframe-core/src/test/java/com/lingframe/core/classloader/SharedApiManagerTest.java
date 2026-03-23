package com.lingframe.core.classloader;

import com.lingframe.core.config.LingFrameConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SharedApiManager 测试")
class SharedApiManagerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        LingClassLoader.resetSharedApiBoundary();
        SharedApiClassLoader.resetInstance();
        LingFrameConfig.clear();
    }

    @Nested
    @DisplayName("共享边界冻结")
    class BoundaryFreezeTests {

        @Test
        @DisplayName("冻结共享边界后应拒绝动态追加共享资源")
        void shouldRejectDynamicAdditionAfterBoundaryIsFrozen() {
            SharedApiManager manager = createManager();
            manager.freezeSharedBoundary();

            assertTrue(manager.isFrozen());
            assertThrows(IllegalStateException.class, () -> manager.addApi(tempDir.toFile()));
        }
    }

    @Nested
    @DisplayName("关闭重置")
    class ShutdownResetTests {

        @Test
        @DisplayName("关闭后应允许重新进入引导期并追加共享资源")
        void shouldAllowBootstrapMutationAgainAfterShutdownReset() {
            SharedApiManager manager = createManager();
            manager.freezeSharedBoundary();
            manager.shutdown();

            assertDoesNotThrow(() -> manager.addApi(tempDir.toFile()));
        }
    }

    private SharedApiManager createManager() {
        LingFrameConfig config = LingFrameConfig.builder()
                .lingHome(tempDir.toString())
                .preloadApiJars(Collections.emptyList())
                .build();
        LingFrameConfig.init(config);
        return new SharedApiManager(ClassLoader.getSystemClassLoader(), config);
    }
}
