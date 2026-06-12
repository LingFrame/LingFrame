package com.lingframe.dashboard.scheduler;

import com.lingframe.core.metrics.JVMMetrics;
import com.lingframe.dashboard.storage.MetricsStorage;
import com.lingframe.dashboard.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时采集 JVM 指标并持久化到 SQLite
 */
@Slf4j
@RequiredArgsConstructor
public class MetricsCollectorScheduler {

    private final MetricsStorage metricsStorage;
    private final StorageProperties properties;

    private ScheduledExecutorService scheduler;

    /**
     * 启动定时采集任务
     */
    public void start() {
        int intervalSeconds = properties.getMetricsCollectIntervalSeconds();
        if (intervalSeconds <= 0) {
            intervalSeconds = 30;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-metrics-collector");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::collectAndSave, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Metrics collector scheduler started, interval: {}s", intervalSeconds);
    }

    /**
     * 停止定时采集任务
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            log.info("Metrics collector scheduler stopped");
        }
    }

    private void collectAndSave() {
        try {
            JVMMetrics metrics = JVMMetrics.collect();
            metricsStorage.saveSnapshot(metrics);
        } catch (Exception e) {
            log.warn("Failed to collect or persist JVM metrics", e);
        }
    }
}
