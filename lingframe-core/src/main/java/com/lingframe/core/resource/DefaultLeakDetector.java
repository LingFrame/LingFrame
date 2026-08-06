package com.lingframe.core.resource;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.runtime.RuntimeMode;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.core.util.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认的类加载器泄漏检测器，采用有界的激进诊断策略。
 */
@Slf4j
public class DefaultLeakDetector implements LeakDetector {

    static final String MODE_DEV_AGGRESSIVE = "DEV_AGGRESSIVE";
    static final String MODE_DEV_BOUNDED = "DEV_BOUNDED";
    static final String MODE_PROD_PASSIVE = "PROD_PASSIVE";

    private final ScheduledThreadPoolExecutor scheduler;
    private final ReferenceQueue<ClassLoader> referenceQueue = new ReferenceQueue<>();
    private final AtomicInteger aggressiveChecksInFlight = new AtomicInteger();

    private final RuntimeMode runtimeMode;
    private final EventBus eventBus;
    private final int maxConcurrentAggressiveChecks;
    private final int devStartDelayMillis;
    private final int aggressiveGcRounds;
    private final int aggressiveGcIntervalMillis;
    private final int passiveWindowMillis;
    private final int finalConfirmationDelayMillis;
    private final int queuePollMillis;

    /**
     * GC 前清理钩子列表。
     * <p>
     * IDE 调试模式下，debugger-agent 会持续拦截 Throwable 创建并记录到 CaptureStorage，
     * 卸载清理后至 GC 窗口期间新捕获的异常 backtrace 仍会强引用灵元 CL 加载的 Class，
     * 阻止 ClassLoader 被 GC 回收。在每轮 GC 前重新执行这些钩子，清除新捕获的条目，
     * 确保 GC 时引用链已断开。
     * <p>
     * 通过 {@link WeakReference#get()} 获取 ClassLoader，避免闭包长期持有强引用。
     */
    private final List<LingUnloadHook> preGcCleaners;

    public DefaultLeakDetector(EventBus eventBus, LingFrameConfig config) {
        this(eventBus, config, Collections.emptyList());
    }

    public DefaultLeakDetector(EventBus eventBus, LingFrameConfig config, List<LingUnloadHook> preGcCleaners) {
        LingFrameConfig effectiveConfig = Objects.requireNonNull(config,
                "LingFrameConfig is required for DefaultLeakDetector");
        this.eventBus = eventBus;
        // 持有 RuntimeMode 引用而非拍平为 boolean：运行时切换 dev/prod 后能实时感知
        this.runtimeMode = effectiveConfig.getRuntimeMode();
        this.maxConcurrentAggressiveChecks = Math.max(1, effectiveConfig.getLeakDetectionMaxConcurrentAggressiveChecks());
        this.devStartDelayMillis = Math.max(0, effectiveConfig.getLeakDetectionDevStartDelayMillis());
        this.aggressiveGcRounds = Math.max(0, effectiveConfig.getLeakDetectionAggressiveGcRounds());
        this.aggressiveGcIntervalMillis = Math.max(1, effectiveConfig.getLeakDetectionAggressiveGcIntervalMillis());
        this.passiveWindowMillis = Math.max(1, effectiveConfig.getLeakDetectionPassiveWindowMillis());
        this.finalConfirmationDelayMillis = Math.max(1, effectiveConfig.getLeakDetectionFinalConfirmationDelayMillis());
        this.queuePollMillis = Math.max(100, effectiveConfig.getLeakDetectionQueuePollMillis());
        this.scheduler = new ScheduledThreadPoolExecutor(
                Math.max(1, this.maxConcurrentAggressiveChecks),
                NamedThreadFactory.daemon("lingframe-leak-detector"));
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.preGcCleaners = preGcCleaners != null
                ? Collections.unmodifiableList(new ArrayList<>(preGcCleaners))
                : Collections.emptyList();

        if (!runtimeMode.isDev()) {
            startQueueListener();
        }
    }

    @Override
    public void detectLeak(String lingId, String version, ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }

        long triggerTimeMillis = System.currentTimeMillis();
        if (runtimeMode.isDev()) {
            detectLeakAggressive(lingId, version, classLoader, triggerTimeMillis);
        } else {
            detectLeakPassive(lingId, version, classLoader, triggerTimeMillis);
        }
    }

    @Override
    public LeakRiskReport checkBefore(String lingId, String version, ClassLoader classLoader) {
        if (classLoader == null) {
            return LeakRiskReport.checkFailed(
                    lingId,
                    version,
                    "Target ClassLoader is unavailable before unload",
                    null,
                    getClass().getName());
        }

        try {
            List<String> threadRisks = findThreadContextClassLoaderRisks(classLoader);
            if (!threadRisks.isEmpty()) {
                return LeakRiskReport.riskDetected(
                        lingId,
                        version,
                        "Detected live threads whose TCCL still points to target ClassLoader",
                        threadRisks,
                        getClass().getName());
            }
            return LeakRiskReport.noRisk(
                    lingId,
                    version,
                    "No obvious pre-unload leak signals detected",
                    Collections.emptyList(),
                    getClass().getName());
        } catch (Exception e) {
            return LeakRiskReport.checkFailed(
                    lingId,
                    version,
                    "Leak precheck failed: " + e.getMessage(),
                    Collections.singletonList(e.getClass().getName()),
                    getClass().getName());
        }
    }

    List<String> findThreadContextClassLoaderRisks(ClassLoader classLoader) {
        Map<Thread, StackTraceElement[]> threadSnapshots = Thread.getAllStackTraces();
        List<String> details = new ArrayList<>();
        for (Thread thread : threadSnapshots.keySet()) {
            if (thread == null || !thread.isAlive()) {
                continue;
            }
            if (thread.getContextClassLoader() == classLoader) {
                details.add("thread=" + thread.getName() + ", state=" + thread.getState());
            }
        }
        return details;
    }

    private void detectLeakAggressive(String lingId, String version, ClassLoader classLoader, long triggerTimeMillis) {
        WeakReference<ClassLoader> reference = new WeakReference<>(classLoader);
        if (!tryAcquireAggressiveSlot()) {
            log.debug("[{}-{}] Aggressive leak detection throttled, using bounded confirmation", lingId, version);
            scheduleBoundedConfirmation(lingId, version, reference, triggerTimeMillis);
            return;
        }

        try {
            scheduler.schedule(() -> {
                try {
                    for (int round = 1; round <= aggressiveGcRounds; round++) {
                        // GC 前清理 debugger-agent 新捕获的异常缓存，防止 backtrace 引用阻止 GC
                        runPreGcCleaners(lingId, reference);
                        System.gc();
                        if (!sleepQuietly(aggressiveGcIntervalMillis)) {
                            return;
                        }
                        if (reference.get() == null) {
                            publishLeakDetection(
                                    lingId,
                                    version,
                                    true,
                                    MODE_DEV_AGGRESSIVE,
                                    triggerTimeMillis,
                                    "ClassLoader collected after " + round + " GC rounds");
                            log.info("✅ [{}-{}] ClassLoader collected successfully (DevMode, GC round {})", lingId, version, round);
                            return;
                        }
                    }
                    scheduleFinalConfirmation(
                            lingId,
                            version,
                            reference,
                            triggerTimeMillis,
                            MODE_DEV_AGGRESSIVE,
                            true,
                            "ClassLoader still alive after " + aggressiveGcRounds + " GC rounds");
                } finally {
                    aggressiveChecksInFlight.decrementAndGet();
                }
            }, devStartDelayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            aggressiveChecksInFlight.decrementAndGet();
            throw ex;
        }
    }

    private void scheduleBoundedConfirmation(String lingId,
                                             String version,
                                             WeakReference<ClassLoader> reference,
                                             long triggerTimeMillis) {
        scheduler.schedule(() -> scheduleFinalConfirmation(
                        lingId,
                        version,
                        reference,
                        triggerTimeMillis,
                        MODE_DEV_BOUNDED,
                        false,
                        "Aggressive leak detection skipped by rate limit"),
                devStartDelayMillis,
                TimeUnit.MILLISECONDS);
    }

    private void scheduleFinalConfirmation(String lingId,
                                           String version,
                                           WeakReference<ClassLoader> reference,
                                           long triggerTimeMillis,
                                           String detectionMode,
                                           boolean triggerGc,
                                           String pendingFailureMessage) {
        scheduler.schedule(() -> {
            if (triggerGc) {
                // 最终确认 GC 前再次清理，防止确认窗口期间新捕获的异常引用阻止 GC
                runPreGcCleaners(lingId, reference);
                System.gc();
            }
            if (reference.get() == null) {
                publishLeakDetection(
                        lingId,
                        version,
                        true,
                        detectionMode,
                        triggerTimeMillis,
                        "ClassLoader collected during confirmation window");
                log.info("✅ [{}-{}] ClassLoader collected successfully", lingId, version);
                return;
            }
            publishLeakDetection(
                    lingId,
                    version,
                    false,
                    detectionMode,
                    triggerTimeMillis,
                    pendingFailureMessage + " and remained alive after confirmation window");
            log.info("❌ [{}-{}] ClassLoader remained alive after confirmation window, {}", lingId, version, pendingFailureMessage);
        }, finalConfirmationDelayMillis, TimeUnit.MILLISECONDS);
    }

    private void detectLeakPassive(String lingId, String version, ClassLoader classLoader, long triggerTimeMillis) {
        LeakReference reference = new LeakReference(
                lingId,
                version,
                MODE_PROD_PASSIVE,
                triggerTimeMillis,
                classLoader,
                referenceQueue);

        scheduler.schedule(() -> {
            if (!reference.tryReport()) {
                return;
            }
            if (reference.get() == null) {
                publishLeakDetection(
                        lingId,
                        version,
                        true,
                        MODE_PROD_PASSIVE,
                        triggerTimeMillis,
                        "ClassLoader collected within passive window");
                log.info("✅ [{}-{}] ClassLoader collected successfully within passive window", lingId, version);
                return;
            }
            publishLeakDetection(
                    lingId,
                    version,
                    false,
                    MODE_PROD_PASSIVE,
                    triggerTimeMillis,
                    "ClassLoader still alive after " + passiveWindowMillis + "ms passive window");
            log.info("❌ [{}-{}] ClassLoader remained alive after {}ms passive window", lingId, version, passiveWindowMillis);
        }, passiveWindowMillis, TimeUnit.MILLISECONDS);
    }

    private void startQueueListener() {
        Thread listener = new Thread(() -> {
            while (!scheduler.isShutdown()) {
                try {
                    LeakReference reference = (LeakReference) referenceQueue.remove(queuePollMillis);
                    if (reference != null && reference.tryReport()) {
                        publishLeakDetection(
                                reference.lingId,
                                reference.version,
                                true,
                                reference.detectionMode,
                                reference.triggerTimeMillis,
                                "ClassLoader collected by JVM natural GC");
                        log.info("✅ [{}-{}] ClassLoader collected successfully by JVM natural GC", reference.lingId, reference.version);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("Queue listener encountered error: {}", e.getMessage());
                }
            }
        }, "lingframe-leak-queue-listener");
        listener.setDaemon(true);
        listener.start();
    }

    @Override
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 在每轮 GC 前执行清理钩子，清除卸载后至 GC 窗口期间新累积的引用。
     * <p>
     * 通过 {@link WeakReference#get()} 获取 ClassLoader：若已回收则跳过清理，
     * 否则短暂持有强引用执行清理。清理完成后显式置 null 释放强引用，
     * 防止 JIT 内联后局部变量跨 System.gc() 存活导致 GC 失效。
     * 单个钩子抛出的异常被捕获并忽略，避免阻塞 GC 流程。
     */
    private void runPreGcCleaners(String lingId, WeakReference<ClassLoader> reference) {
        if (preGcCleaners.isEmpty()) {
            return;
        }
        ClassLoader classLoader = reference.get();
        if (classLoader == null) {
            return;
        }
        try {
            for (LingUnloadHook cleaner : preGcCleaners) {
                try {
                    cleaner.cleanup(lingId, classLoader);
                } catch (Throwable t) {
                    log.debug("[{}] Pre-GC cleaner {} failed: {}", lingId, cleaner.getClass().getSimpleName(), t.getMessage());
                }
            }
        } finally {
            // 显式释放强引用：JIT 内联本方法时，局部变量可能跨 System.gc() 存活，
            // 导致 ClassLoader 在 GC 窗口期间被线程栈持有而无法回收。
            classLoader = null;
        }
    }

    private boolean tryAcquireAggressiveSlot() {
        while (true) {
            int current = aggressiveChecksInFlight.get();
            if (current >= maxConcurrentAggressiveChecks) {
                return false;
            }
            if (aggressiveChecksInFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void publishLeakDetection(String lingId,
                                      String version,
                                      boolean collected,
                                      String detectionMode,
                                      long triggerTimeMillis,
                                      String message) {
        if (eventBus == null) {
            return;
        }
        try {
            eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                    lingId,
                    version,
                    collected,
                    message,
                    detectionMode,
                    triggerTimeMillis));
        } catch (Exception e) {
            log.warn("Failed to publish leak detection event: {}", e.getMessage());
        }
    }

    private static final class LeakReference extends WeakReference<ClassLoader> {
        private final String lingId;
        private final String version;
        private final String detectionMode;
        private final long triggerTimeMillis;
        private final AtomicBoolean reported = new AtomicBoolean(false);

        private LeakReference(String lingId,
                              String version,
                              String detectionMode,
                              long triggerTimeMillis,
                              ClassLoader referent,
                              ReferenceQueue<? super ClassLoader> queue) {
            super(referent, queue);
            this.lingId = lingId;
            this.version = version;
            this.detectionMode = detectionMode;
            this.triggerTimeMillis = triggerTimeMillis;
        }

        private boolean tryReport() {
            return reported.compareAndSet(false, true);
        }
    }
}
