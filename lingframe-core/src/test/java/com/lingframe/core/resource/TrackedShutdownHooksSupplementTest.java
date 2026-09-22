package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TrackedShutdownHooks} 的补充测试。
 * <p>
 * 该类是静态工具类，内部维护 {@code ClassLoader -> Set<Thread>} 跟踪表。
 * 由于状态为 static，每个测试使用独立的 ClassLoader 实例避免相互干扰，
 * 并在 finally 中调用 cleanupFor 清理，防止 ShutdownHook 残留。
 */
@DisplayName("TrackedShutdownHooks 补充测试")
class TrackedShutdownHooksSupplementTest {

    @Test
    @DisplayName("register null hook 应无操作")
    void shouldNotRegisterNullHook() {
        ClassLoader cl = new ClassLoader() {
        };
        try {
            assertDoesNotThrow(() -> TrackedShutdownHooks.register(null, cl));
            assertFalse(TrackedShutdownHooks.hasTrackedHooks(cl));
        } finally {
            TrackedShutdownHooks.cleanupFor(cl);
        }
    }

    @Test
    @DisplayName("register null ClassLoader 应无操作")
    void shouldNotRegisterForNullClassLoader() {
        Thread hook = new Thread(() -> {
        });
        try {
            assertDoesNotThrow(() -> TrackedShutdownHooks.register(hook, null));
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (Exception ignored) {
                // 未注册则忽略
            }
        }
    }

    @Test
    @DisplayName("register 有效 hook 后应可被 getTrackedHooks/hasTrackedHooks 查到")
    void shouldRegisterAndQueryHook() {
        ClassLoader cl = new ClassLoader() {
        };
        Thread hook = new Thread(() -> {
        });
        try {
            TrackedShutdownHooks.register(hook, cl);
            assertTrue(TrackedShutdownHooks.hasTrackedHooks(cl));
            Set<Thread> tracked = TrackedShutdownHooks.getTrackedHooks(cl);
            assertNotNull(tracked);
            assertTrue(tracked.contains(hook));
        } finally {
            TrackedShutdownHooks.cleanupFor(cl);
        }
    }

    @Test
    @DisplayName("cleanupFor null 应返回 0")
    void shouldReturnZeroForNullClassLoader() {
        assertEquals(0, TrackedShutdownHooks.cleanupFor(null));
    }

    @Test
    @DisplayName("cleanupFor 未注册的 ClassLoader 应返回 0")
    void shouldReturnZeroForUnregisteredClassLoader() {
        ClassLoader cl = new ClassLoader() {
        };
        assertEquals(0, TrackedShutdownHooks.cleanupFor(cl));
    }

    @Test
    @DisplayName("cleanupFor 注册过的 ClassLoader 应移除 Hook 并返回数量")
    void shouldCleanupRegisteredHooks() {
        ClassLoader cl = new ClassLoader() {
        };
        Thread hook1 = new Thread(() -> {
        });
        Thread hook2 = new Thread(() -> {
        });
        TrackedShutdownHooks.register(hook1, cl);
        TrackedShutdownHooks.register(hook2, cl);
        assertEquals(2, TrackedShutdownHooks.cleanupFor(cl));
        assertFalse(TrackedShutdownHooks.hasTrackedHooks(cl));
    }

    @Test
    @DisplayName("cleanupFor 后再次 cleanup 应返回 0（幂等）")
    void shouldBeIdempotentAfterCleanup() {
        ClassLoader cl = new ClassLoader() {
        };
        Thread hook = new Thread(() -> {
        });
        try {
            TrackedShutdownHooks.register(hook, cl);
            assertEquals(1, TrackedShutdownHooks.cleanupFor(cl));
            assertEquals(0, TrackedShutdownHooks.cleanupFor(cl));
        } finally {
            TrackedShutdownHooks.cleanupFor(cl);
        }
    }

    @Test
    @DisplayName("getTrackedHooks 未注册的 ClassLoader 应返回 null")
    void shouldReturnNullForUnregistered() {
        ClassLoader cl = new ClassLoader() {
        };
        assertNull(TrackedShutdownHooks.getTrackedHooks(cl));
    }

    @Test
    @DisplayName("hasTrackedHooks 未注册的 ClassLoader 应返回 false")
    void shouldReturnFalseForUnregistered() {
        ClassLoader cl = new ClassLoader() {
        };
        assertFalse(TrackedShutdownHooks.hasTrackedHooks(cl));
    }
}
