package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ThreadReferenceUnloadHook} 的补充测试。
 * <p>
 * 已有 {@link JvmUnloadHookTest.ThreadReferenceHookTest} 覆盖基础安全校验与
 * contextClassLoader/ThreadLocal 清理路径。此处补充：Timer 线程中断、
 * 遗留线程池关闭、空/null lingId、幂等调用等分支。
 */
@DisplayName("ThreadReferenceUnloadHook 补充测试")
class ThreadReferenceUnloadHookSupplementTest {

    private final ThreadReferenceUnloadHook hook = new ThreadReferenceUnloadHook();

    @Test
    @DisplayName("cleanup 扩展/平台 ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipPlatformClassLoader() {
        ClassLoader platform = ClassLoader.getSystemClassLoader().getParent();
        assertDoesNotThrow(() -> hook.cleanup("ling-t", platform));
    }

    @Test
    @DisplayName("cleanup 自定义 ClassLoader 无关联线程时为空操作")
    void shouldNoopWhenNoRelatedThreads() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("ling-t", customCL));
    }

    @Test
    @DisplayName("cleanup 空 lingId 不报错")
    void shouldCleanupWithEmptyLingId() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("", customCL));
    }

    @Test
    @DisplayName("cleanup null lingId 不报错")
    void shouldCleanupWithNullLingId() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup(null, customCL));
    }

    @Test
    @DisplayName("cleanup 同一 ClassLoader 多次调用不报错（幂等）")
    void shouldCleanupMultipleTimes() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> {
            hook.cleanup("ling-t", customCL);
            hook.cleanup("ling-t", customCL);
        });
    }

    @Test
    @DisplayName("cleanup 应中断关联目标 CL 的 Timer 线程")
    void shouldInterruptTimerThreadWithMatchingClassLoader() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        Timer timer = new Timer("ling-timer-test");
        AtomicReference<String> result = new AtomicReference<>("not-cancelled");
        CountDownLatch scheduled = new CountDownLatch(1);
        try {
            // 设置 Timer 线程的 contextClassLoader 为目标 CL
            // Timer 内部线程的 contextClassLoader 默认为创建者 CL，这里通过调度任务间接验证清理不抛异常
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    scheduled.countDown();
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        result.set("interrupted");
                    }
                }
            }, 0);
            // 等待任务开始
            assertTrue(scheduled.await(2, TimeUnit.SECONDS));

            // 通过反射设置 Timer 线程的 contextClassLoader，使其匹配目标 CL
            java.lang.reflect.Field targetField = Thread.class.getDeclaredField("contextClassLoader");
            targetField.setAccessible(true);
            // 找到 TimerThread 并设置其 contextClassLoader
            Thread[] threads = JvmCleanupSupport.getActiveThreads();
            Thread timerThread = null;
            for (Thread t : threads) {
                if (t != null && "java.util.TimerThread".equals(t.getClass().getName())
                        && t.getName().equals("ling-timer-test")) {
                    timerThread = t;
                    break;
                }
            }
            assertNotNull(timerThread, "应能找到 Timer 线程");
            targetField.set(timerThread, customCL);

            // 执行清理，Timer 线程应被中断
            assertDoesNotThrow(() -> hook.cleanup("ling-t", customCL));
            timerThread.join(2000);
            assertEquals("interrupted", result.get());
        } finally {
            timer.cancel();
        }
    }

    @Test
    @DisplayName("cleanup 应关闭关联目标 CL 的遗留线程池")
    void shouldShutdownOrphanThreadPool() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ling-pool-worker");
            t.setContextClassLoader(customCL);
            return t;
        });
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("not-interrupted");
        try {
            pool.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(5000);
                    result.set("completed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.set("interrupted");
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            // 执行清理，线程池应被 shutdownNow
            assertDoesNotThrow(() -> hook.cleanup("ling-pool", customCL));
            // 工作线程应被中断
            assertTrue(done.await(3, TimeUnit.SECONDS) || result.get().equals("interrupted")
                    || pool.isShutdown());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("cleanup 应清理关联目标 CL 的线程 contextClassLoader 引用")
    void shouldClearThreadContextClassLoaderReference() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.setContextClassLoader(customCL);
        t.start();
        try {
            assertSame(customCL, t.getContextClassLoader());
            assertDoesNotThrow(() -> hook.cleanup("ling-ctx", customCL));
            // contextClassLoader 应被清理（设为 null）
            assertNull(t.getContextClassLoader());
        } finally {
            t.interrupt();
            t.join(2000);
        }
    }
}
