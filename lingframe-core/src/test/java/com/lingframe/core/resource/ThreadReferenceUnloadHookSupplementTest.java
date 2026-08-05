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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ThreadReferenceUnloadHook} 的补充测试。
 * <p>
 * 已有 {@link JvmUnloadHookTest.ThreadReferenceHookTest} 覆盖基础安全校验与
 * contextClassLoader/ThreadLocal 清理路径。此处补充：Timer 线程中断、
 * 遗留线程池关闭、空/null lingId、幂等调用等分支。
 * <p>
 * Timer 线程的 contextClassLoader 通过创建前设置当前线程 TCCL 继承，
 * 避免反射写 {@code Thread.contextClassLoader}（JDK 16+ 需 add-opens）。
 * 遗留线程池关闭依赖反射读取 {@code Thread.target}，缺
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED} 时跳过深度断言。
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
        AtomicReference<String> result = new AtomicReference<>("not-cancelled");
        CountDownLatch scheduled = new CountDownLatch(1);
        Timer timer = null;
        ClassLoader previousTCCL = Thread.currentThread().getContextClassLoader();
        try {
            // TimerThread 创建时继承创建者 TCCL，避免反射写 Thread.contextClassLoader
            Thread.currentThread().setContextClassLoader(customCL);
            timer = new Timer("ling-timer-test");
        } finally {
            Thread.currentThread().setContextClassLoader(previousTCCL);
        }
        try {
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
            assertTrue(scheduled.await(2, TimeUnit.SECONDS), "Timer 任务应已开始");

            Thread timerThread = findTimerThread("ling-timer-test");
            assertNotNull(timerThread, "应能找到 Timer 线程");
            assertSame(customCL, timerThread.getContextClassLoader(),
                    "Timer 线程应继承目标 ClassLoader 作为 TCCL");

            // 执行清理，Timer 线程应被中断
            assertDoesNotThrow(() -> hook.cleanup("ling-t", customCL));
            timerThread.join(2000);
            assertEquals("interrupted", result.get(), "Timer 任务应因线程中断而退出");
        } finally {
            if (timer != null) {
                timer.cancel();
            }
        }
    }

    @Test
    @DisplayName("cleanup 应关闭关联目标 CL 的遗留线程池")
    void shouldShutdownOrphanThreadPool() throws Exception {
        // 从 Worker 追溯 ThreadPoolExecutor 依赖 Thread.target 反射
        assumeTrue(JvmCleanupSupport.THREAD_TARGET_FIELD != null,
                "需要 --add-opens java.base/java.lang=ALL-UNNAMED 以反射读取 Thread.target 关闭遗留线程池");

        ClassLoader customCL = new ClassLoader() {
        };
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ling-pool-worker");
            t.setContextClassLoader(customCL);
            return t;
        });
        CountDownLatch started = new CountDownLatch(1);
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
            assertTrue(started.await(2, TimeUnit.SECONDS), "工作线程应已启动");

            // 执行清理，线程池应被 shutdownNow
            assertDoesNotThrow(() -> hook.cleanup("ling-pool", customCL));
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS) || pool.isShutdown()
                            || "interrupted".equals(result.get()),
                    "线程池应被 shutdown，或工作线程被中断；actual state="
                            + (pool.isShutdown() ? "shutdown" : "running")
                            + ", result=" + result.get());
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    /** 按名称定位活动中的 java.util.TimerThread */
    private static Thread findTimerThread(String name) {
        for (Thread t : JvmCleanupSupport.getActiveThreads()) {
            if (t != null
                    && "java.util.TimerThread".equals(t.getClass().getName())
                    && name.equals(t.getName())) {
                return t;
            }
        }
        return null;
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
