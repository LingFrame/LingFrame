package com.lingframe.core.resource;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.spi.LeakDetector;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 默认泄露检测器实现
 *
 * <p>采用"环境感知"策略，根据运行模式选择不同的检测方式：
 *
 * <h3>开发模式 (devMode=true)</h3>
 * <ul>
 *   <li>激进检测：主动诱导 GC 并循环检查</li>
 *   <li>延迟 2 秒后开始检测</li>
 *   <li>最多执行 5 轮 GC，每轮间隔 500ms</li>
 *   <li>立即反馈结果，便于开发调试</li>
 * </ul>
 *
 * <h3>生产模式 (devMode=false)</h3>
 * <ul>
 *   <li>静默监听：依赖 ReferenceQueue 和长周期超时</li>
 *   <li>零侵入，不主动触发 GC</li>
 *   <li>60秒后进行"最终审判"</li>
 *   <li>通过队列监听线程确认回收</li>
 * </ul>
 *
 * <p>检测结果通过 EventBus 推送到 Dashboard：
 * <ul>
 *   <li>成功回收：INFO 级别，显示 GC 轮次或时间窗口</li>
 *   <li>疑似泄漏：ERROR 级别，提示可能存在内存泄漏</li>
 * </ul>
 *
 * @see MonitoringEvents.LeakDetectionEvent
 */
@Slf4j
public class DefaultLeakDetector implements LeakDetector {

    private final ScheduledExecutorService scheduler;
    private final ReferenceQueue<ClassLoader> referenceQueue = new ReferenceQueue<>();
    private final boolean devMode;
    private final EventBus eventBus;

    /**
     * 默认构造器（从配置读取模式）
     */
    public DefaultLeakDetector() {
        this(null, LingFrameConfig.current());
    }

    /**
     * 完整构造器
     *
     * @param eventBus 事件总线，用于推送检测结果
     * @param config   框架配置，用于判断运行模式
     */
    public DefaultLeakDetector(EventBus eventBus, LingFrameConfig config) {
        this.eventBus = eventBus;
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

    /**
     * 检测 ClassLoader 是否存在内存泄漏
     *
     * @param lingId      灵元ID
     * @param classLoader 待检测的 ClassLoader
     */
    @Override
    public void detectLeak(String lingId, String version, ClassLoader classLoader) {
        if (classLoader == null) return;

        if (devMode) {
            detectLeakAggressive(lingId, version, classLoader);
        } else {
            detectLeakPassive(lingId, version, classLoader);
        }
    }

    /**
     * [开发模式] 激进检测：诱导 GC 并循环检查
     *
     * <p>执行策略：
     * <ol>
     *   <li>延迟 2 秒后开始检测</li>
     *   <li>每轮执行 System.gc() 并等待 500ms</li>
     *   <li>检查弱引用是否被回收</li>
     *   <li>最多 5 轮，成功则提前退出</li>
     * </ol>
     */
    private void detectLeakAggressive(String lingId, String version, ClassLoader classLoader) {
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
                    log.info("✅ [{}-{}] ClassLoader collected successfully (DevMode, GC round {})", lingId, version, i + 1);
                    publishLeakDetection(lingId, version, true, "ClassLoader collected successfully after " + (i + 1) + " GC rounds");
                    return;
                }
            }
            log.warn("⚠️ [{}-{}] ClassLoader NOT collected after 5 GC rounds! (DevMode)", lingId, version);
            publishLeakDetection(lingId, version, false, "ClassLoader NOT collected after 5 GC rounds - Memory leak suspected!");
        }, 2, TimeUnit.SECONDS);
    }

    /**
     * [生产模式] 静默监听：依赖 ReferenceQueue 和长周期超时
     *
     * <p>执行策略：
     * <ol>
     *   <li>创建带队列的弱引用</li>
     *   <li>60秒后进行"最终审判"</li>
     *   <li>检查引用是否仍存活</li>
     *   <li>队列监听线程可提前确认回收</li>
     * </ol>
     */
    private void detectLeakPassive(String lingId, String version, ClassLoader classLoader) {
        // 使用带队列的弱引用，以便异步确认回收
        LeakReference ref = new LeakReference(lingId, version, classLoader, referenceQueue);

        // 60秒后进行"最终审判"
        scheduler.schedule(() -> {
            if (ref.get() != null) {
                log.warn("⚠️ [{}-{}] ClassLoader still alive after 60s window. Memory leak suspected! (ProdMode)", lingId, version);
                publishLeakDetection(lingId, version, false, "ClassLoader still alive after 60s - Memory leak suspected!");
            } else {
                // 如果引用的对象已经不在了，但还没出现在队列里，说明回收在最后一刻发生了
                log.debug("[{}-{}] Final check confirmed ClassLoader collection.", lingId, version);
                publishLeakDetection(lingId, version, true, "ClassLoader collected within 60s window");
            }
        }, 60, TimeUnit.SECONDS);
    }

    /**
     * 启动队列监听线程（仅生产模式）
     *
     * <p>该线程阻塞等待 JVM 自然 GC 将引用放入队列，
     * 可提前确认 ClassLoader 已被回收，无需等待 60 秒超时。
     */
    private void startQueueListener() {
        Thread t = new Thread(() -> {
            while (!scheduler.isShutdown()) {
                try {
                    // 阻塞等待
                    LeakReference ref = (LeakReference) referenceQueue.remove(5000);
                    if (ref != null) {
                        log.info("✅ [{}-{}] ClassLoader collected by JVM natural GC.", ref.lingId, ref.version);
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

    /**
     * 关闭检测器，释放资源
     */
    @Override
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 携带元数据的弱引用
     *
     * <p>用于在 ReferenceQueue 中识别是哪个灵元的 ClassLoader 被回收
     */
    private static class LeakReference extends WeakReference<ClassLoader> {
        final String lingId;
        final String version;

        LeakReference(String lingId, String version, ClassLoader referent, ReferenceQueue<? super ClassLoader> q) {
            super(referent, q);
            this.lingId = lingId;
            this.version = version;
        }
    }

    /**
     * 发布泄漏检测结果事件
     *
     * @param lingId    灵元ID
     * @param collected 是否成功回收
     * @param message   结果消息
     */
    private void publishLeakDetection(String lingId, String version, boolean collected, String message) {
        if (eventBus != null) {
            try {
                eventBus.publish(new MonitoringEvents.LeakDetectionEvent(lingId, version, collected, message));
            } catch (Exception e) {
                log.warn("Failed to publish leak detection event: {}", e.getMessage());
            }
        }
    }
}
