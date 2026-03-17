package com.lingframe.core.resource;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.spi.LeakDetector;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 默认泄露检测器实现
 * <p>
 * 采用“环境感知”策略：
 * 1. 生产模式：完全基于 ReferenceQueue 监听，配合长周期超时判定，零侵入。
 * 2. 开发模式：维持激进的 System.gc() 诱导逻辑，确保问题即时暴露。
 */
@Slf4j
public class DefaultLeakDetector implements LeakDetector {

    private final ScheduledExecutorService scheduler;
    private final ReferenceQueue<ClassLoader> referenceQueue = new ReferenceQueue<>();
    private final boolean devMode;

    public DefaultLeakDetector() {
        this(LingFrameConfig.current());
    }

    public DefaultLeakDetector(LingFrameConfig config) {
        this.devMode = config != null ? config.isDevMode() : LingFrameConfig.current().isDevMode();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-leak-detector");
            t.setDaemon(true);
            return t;
        });

        // 启动队列监听线程 (仅用于生产模式下的即时回收确认)
        if (!devMode) {
            startQueueListener();
        }
    }

    @Override
    public void detectLeak(String lingId, ClassLoader classLoader) {
        if (classLoader == null) return;

        if (devMode) {
            detectLeakAggressive(lingId, classLoader);
        } else {
            detectLeakPassive(lingId, classLoader);
        }
    }

    /**
     * [开发模式] 激进检测：诱导 GC 并循环检查
     */
    private void detectLeakAggressive(String lingId, ClassLoader classLoader) {
        WeakReference<ClassLoader> ref = new WeakReference<>(classLoader);
        scheduler.schedule(() -> {
            for (int i = 0; i < 5; i++) {
                System.gc();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (ref.get() == null) {
                    log.info("✅ [{}] ClassLoader collected successfully (DevMode, GC round {})", lingId, i + 1);
                    return;
                }
            }
            log.warn("⚠️ [{}] ClassLoader NOT collected after 5 GC rounds! (DevMode)", lingId);
        }, 2, TimeUnit.SECONDS); // 稍微延迟一下给 cleanup 留时间
    }

    /**
     * [生产模式] 静默监听：依赖 ReferenceQueue 和长周期超时
     */
    private void detectLeakPassive(String lingId, ClassLoader classLoader) {
        // 使用带队列的弱引用，以便异步确认回收
        LeakReference ref = new LeakReference(lingId, classLoader, referenceQueue);
        
        // 60秒后进行“最终审判”
        scheduler.schedule(() -> {
            if (ref.get() != null) {
                log.warn("⚠️ [{}] ClassLoader still alive after 60s window. Memory leak suspected! (ProdMode)", lingId);
            } else {
                // 如果引用的对象已经不在了，但还没出现在队列里，说明回收在最后一刻发生了
                log.debug("[{}] Final check confirmed ClassLoader collection.", lingId);
            }
        }, 60, TimeUnit.SECONDS);
    }

    private void startQueueListener() {
        Thread t = new Thread(() -> {
            while (!scheduler.isShutdown()) {
                try {
                    // 阻塞等待
                    LeakReference ref = (LeakReference) referenceQueue.remove(5000);
                    if (ref != null) {
                        log.info("✅ [{}] ClassLoader collected by JVM natural GC.", ref.lingId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("Queue listener encountered error: {}", e.getMessage());
                }
            }
        }, "lingframe-leak-queue-listener");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 携带元数据的弱引用
     */
    private static class LeakReference extends WeakReference<ClassLoader> {
        final String lingId;

        LeakReference(String lingId, ClassLoader referent, ReferenceQueue<? super ClassLoader> q) {
            super(referent, q);
            this.lingId = lingId;
        }
    }
}
