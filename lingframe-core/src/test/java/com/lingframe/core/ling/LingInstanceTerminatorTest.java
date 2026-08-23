package com.lingframe.core.ling;

import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LingInstanceTerminator 测试。
 * 覆盖：容器停止、clearDetachedState、null 安全。
 */
@DisplayName("LingInstanceTerminator 测试")
class LingInstanceTerminatorTest {

    private final LingInstanceTerminator terminator = new LingInstanceTerminator();

    // ==================== 容器停止 ====================

    @Nested
    @DisplayName("容器停止")
    class ContainerStop {

        @Test
        @DisplayName("活跃容器调用 stop()")
        void activeContainerStopped() {
            LingContainer container = mock(LingContainer.class);
            when(container.isActive()).thenReturn(true);

            LingInstance instance = mock(LingInstance.class);
            when(instance.getContainer()).thenReturn(container);
            when(instance.getLingId()).thenReturn("ling-1");
            when(instance.getVersion()).thenReturn("v1");

            terminator.terminate(instance);

            verify(container).stop();
        }

        @Test
        @DisplayName("非活跃容器也调用 stop()（stop 幂等兜底，保证卸载 hook 必达）")
        void inactiveContainerStillStopped() {
            LingContainer container = mock(LingContainer.class);
            when(container.isActive()).thenReturn(false);

            LingInstance instance = mock(LingInstance.class);
            when(instance.getContainer()).thenReturn(container);
            when(instance.getLingId()).thenReturn("ling-1");
            when(instance.getVersion()).thenReturn("v1");

            terminator.terminate(instance);

            // 去掉 isActive 守卫，container 非 null 即无条件 stop()。
            // SpringLingContainer.stop() 是 CAS 幂等，重复/提前停止都安全，
            // 但跳过 stop() 会导致 Spring context 不关、HikariCP 连接池不关 → ClassLoader 永久泄漏。
            verify(container).stop();
        }

        @Test
        @DisplayName("容器 stop() 抛异常不传播")
        void containerStopExceptionSwallowed() {
            LingContainer container = mock(LingContainer.class);
            when(container.isActive()).thenReturn(true);
            doThrow(new RuntimeException("stop error")).when(container).stop();

            LingInstance instance = mock(LingInstance.class);
            when(instance.getContainer()).thenReturn(container);
            when(instance.getLingId()).thenReturn("ling-1");
            when(instance.getVersion()).thenReturn("v1");

            assertDoesNotThrow(() -> terminator.terminate(instance));
        }
    }

    // ==================== clearDetachedState ====================

    @Nested
    @DisplayName("clearDetachedState")
    class ClearDetachedState {

        @Test
        @DisplayName("terminate 后调用 clearDetachedState")
        void clearDetachedStateCalled() {
            LingContainer container = mock(LingContainer.class);
            when(container.isActive()).thenReturn(true);

            LingInstance instance = mock(LingInstance.class);
            when(instance.getContainer()).thenReturn(container);
            when(instance.getLingId()).thenReturn("ling-1");
            when(instance.getVersion()).thenReturn("v1");

            terminator.terminate(instance);

            verify(instance).clearDetachedState();
        }
    }

    // ==================== null 安全 ====================

    @Nested
    @DisplayName("null 安全")
    class NullSafety {

        @Test
        @DisplayName("null 实例不抛异常")
        void nullInstanceSafe() {
            assertDoesNotThrow(() -> terminator.terminate(null));
        }

        @Test
        @DisplayName("容器为 null 时不抛异常")
        void nullContainerSafe() {
            LingInstance instance = mock(LingInstance.class);
            when(instance.getContainer()).thenReturn(null);
            when(instance.getLingId()).thenReturn("ling-1");
            when(instance.getVersion()).thenReturn("v1");

            assertDoesNotThrow(() -> terminator.terminate(instance));
            verify(instance).clearDetachedState();
        }
    }
}
