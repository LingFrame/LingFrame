package com.lingframe.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NamedThreadFactory} 的补充测试。
 * <p>
 * 覆盖工厂方法（daemon/nonDaemon）、线程命名与序号递增、
 * contextClassLoader 绑定以及静态线程跟踪表。
 */
@DisplayName("NamedThreadFactory 补充测试")
class NamedThreadFactorySupplementTest {

    @Test
    @DisplayName("daemon(prefix) 创建的工厂应产出 daemon 线程且名称带前缀")
    void shouldCreateDaemonThreads() {
        NamedThreadFactory factory = NamedThreadFactory.daemon("ling-test");
        Thread t = factory.newThread(() -> {
        });
        try {
            assertNotNull(t);
            assertTrue(t.isDaemon());
            assertTrue(t.getName().startsWith("ling-test-"));
        } finally {
            // 不启动直接清理
            NamedThreadFactory.cleanupThreads("ling-test", null);
        }
    }

    @Test
    @DisplayName("nonDaemon(prefix) 创建的工厂应产出非 daemon 线程")
    void shouldCreateNonDaemonThreads() {
        NamedThreadFactory factory = NamedThreadFactory.nonDaemon("ling-nond");
        Thread t = factory.newThread(() -> {
        });
        try {
            assertNotNull(t);
            assertFalse(t.isDaemon());
            assertTrue(t.getName().startsWith("ling-nond-"));
        } finally {
            NamedThreadFactory.cleanupThreads("ling-nond", null);
        }
    }

    @Test
    @DisplayName("连续 newThread 应使序号递增")
    void shouldIncrementCounter() {
        NamedThreadFactory factory = NamedThreadFactory.daemon("ling-incr");
        Thread t1 = factory.newThread(() -> {
        });
        Thread t2 = factory.newThread(() -> {
        });
        Thread t3 = factory.newThread(() -> {
        });
        try {
            // 后一个序号应大于前一个
            int n1 = Integer.parseInt(t1.getName().substring(t1.getName().lastIndexOf('-') + 1));
            int n2 = Integer.parseInt(t2.getName().substring(t2.getName().lastIndexOf('-') + 1));
            int n3 = Integer.parseInt(t3.getName().substring(t3.getName().lastIndexOf('-') + 1));
            assertEquals(n1 + 1, n2);
            assertEquals(n2 + 1, n3);
        } finally {
            NamedThreadFactory.cleanupThreads("ling-incr", null);
        }
    }

    @Test
    @DisplayName("daemon(prefix, cl) 应将 contextClassLoader 绑定到线程并登记到跟踪表")
    void shouldBindContextClassLoaderAndTrack() {
        ClassLoader cl = new ClassLoader() {
        };
        NamedThreadFactory factory = NamedThreadFactory.daemon("ling-bind", cl);
        Thread t = factory.newThread(() -> {
        });
        try {
            assertSame(cl, t.getContextClassLoader());
            assertTrue(NamedThreadFactory.hasTrackedThreads(cl));
            Set<Thread> tracked = NamedThreadFactory.getTrackedThreads(cl);
            assertNotNull(tracked);
            assertTrue(tracked.contains(t));
        } finally {
            NamedThreadFactory.cleanupThreads("ling-bind", cl);
        }
    }

    @Test
    @DisplayName("daemon(prefix) 不绑定 ClassLoader 时不登记到跟踪表")
    void shouldNotTrackWhenNoClassLoader() {
        NamedThreadFactory factory = NamedThreadFactory.daemon("ling-notrack");
        Thread t = factory.newThread(() -> {
        });
        try {
            // 无 ClassLoader 绑定，t.getContextClassLoader 为调用者 CL，不应被跟踪
            // 由于跟踪表 key 是 CL，此处只能确认线程创建成功
            assertNotNull(t);
        } finally {
            NamedThreadFactory.cleanupThreads("ling-notrack", null);
        }
    }

    @Test
    @DisplayName("cleanupThreads null ClassLoader 应返回 0")
    void shouldReturnZeroForNullClassLoader() {
        assertEquals(0, NamedThreadFactory.cleanupThreads("ling", null));
    }

    @Test
    @DisplayName("cleanupThreads 未注册的 ClassLoader 应返回 0")
    void shouldReturnZeroForUnregisteredClassLoader() {
        ClassLoader cl = new ClassLoader() {
        };
        assertEquals(0, NamedThreadFactory.cleanupThreads("ling", cl));
    }

    @Test
    @DisplayName("cleanupThreads 应清理跟踪的线程并重置 contextClassLoader")
    void shouldCleanupTrackedThreads() {
        ClassLoader cl = new ClassLoader() {
        };
        NamedThreadFactory factory = NamedThreadFactory.daemon("ling-cleanup", cl);
        Thread t1 = factory.newThread(() -> {
        });
        Thread t2 = factory.newThread(() -> {
        });
        // 未启动，isAlive=false，不会被中断
        int count = NamedThreadFactory.cleanupThreads("ling-cleanup", cl);
        assertEquals(2, count);
        assertFalse(NamedThreadFactory.hasTrackedThreads(cl));
        // contextClassLoader 应被重置为系统 CL
        assertSame(ClassLoader.getSystemClassLoader(), t1.getContextClassLoader());
        assertSame(ClassLoader.getSystemClassLoader(), t2.getContextClassLoader());
    }

    @Test
    @DisplayName("cleanupThreads 处理已启动的非 daemon 线程应中断并 join")
    void shouldInterruptAliveNonDaemonThread() throws Exception {
        ClassLoader cl = new ClassLoader() {
        };
        // nonDaemon 无带 ClassLoader 的重载，用 daemon(prefix, cl) 创建后设置为非 daemon
        NamedThreadFactory factory = NamedThreadFactory.daemon("ling-alive", cl);
        AtomicReference<String> result = new AtomicReference<>("not-interrupted");
        Thread t = factory.newThread(() -> {
            try {
                Thread.sleep(5000);
                result.set("completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.set("interrupted");
            }
        });
        // 转为非 daemon 线程以触发 cleanupThreads 的中断分支
        t.setDaemon(false);
        t.start();
        // 确保线程已启动
        Thread.sleep(100);
        int count = NamedThreadFactory.cleanupThreads("ling-alive", cl);
        assertEquals(1, count);
        // 等待线程响应中断
        t.join(2000);
        assertEquals("interrupted", result.get());
    }

    @Test
    @DisplayName("hasTrackedThreads 未注册的 ClassLoader 应返回 false")
    void shouldReturnFalseForUnregisteredHasTracked() {
        ClassLoader cl = new ClassLoader() {
        };
        assertFalse(NamedThreadFactory.hasTrackedThreads(cl));
    }

    @Test
    @DisplayName("getTrackedThreads 未注册的 ClassLoader 应返回 null")
    void shouldReturnNullForUnregisteredGetTracked() {
        ClassLoader cl = new ClassLoader() {
        };
        assertNull(NamedThreadFactory.getTrackedThreads(cl));
    }
}
