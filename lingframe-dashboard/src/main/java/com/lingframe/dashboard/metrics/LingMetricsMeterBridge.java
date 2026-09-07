package com.lingframe.dashboard.metrics;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingUnloadCoordinator;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.GovernanceMetricsSnapshot;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.api.event.LingEventListener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LingMetricsMeterBridge implements InitializingBean, DisposableBean {

    private final MetricsCollector metricsCollector;
    private final GovernanceMetricsCollector governanceMetricsCollector;
    private final LingRepository lingRepository;
    private final LingUnloadCoordinator unloadCoordinator;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;

    private final MultiGauge lingHealthQps;
    private final MultiGauge lingHealthErrorRate;
    private final MultiGauge lingHealthP99Latency;
    private final MultiGauge lingHealthActiveRequests;
    private final MultiGauge versionHealthQps;
    private final MultiGauge versionHealthErrorRate;
    private final MultiGauge governanceRateLimited;
    private final MultiGauge governanceTimeouts;
    private final MultiGauge governanceCircuitOpened;
    private final MultiGauge governanceCircuitRejected;
    private final MultiGauge governancePropagationSuccess;
    private final MultiGauge governancePropagationFailure;
    private final Gauge activeLingCount;
    private final Gauge versionUnloadCount;
    private final Gauge lingUnloadCount;
    private final Gauge versionUnloadLastDurationMs;
    private final Gauge lingUnloadLastDurationMs;
    private final Gauge leakAlertTotal;
    private final AtomicLong leakAlertCount = new AtomicLong();

    public LingMetricsMeterBridge(MeterRegistry meterRegistry,
                                  MetricsCollector metricsCollector,
                                  GovernanceMetricsCollector governanceMetricsCollector) {
        this(meterRegistry, metricsCollector, governanceMetricsCollector, null, null, null);
    }

    public LingMetricsMeterBridge(MeterRegistry meterRegistry,
                                  MetricsCollector metricsCollector,
                                  GovernanceMetricsCollector governanceMetricsCollector,
                                  LingRepository lingRepository,
                                  LingUnloadCoordinator unloadCoordinator) {
        this(meterRegistry, metricsCollector, governanceMetricsCollector, lingRepository, unloadCoordinator, null);
    }

    public LingMetricsMeterBridge(MeterRegistry meterRegistry,
                                  MetricsCollector metricsCollector,
                                  GovernanceMetricsCollector governanceMetricsCollector,
                                  LingRepository lingRepository,
                                  LingUnloadCoordinator unloadCoordinator,
                                  EventBus eventBus) {
        this.metricsCollector = metricsCollector;
        this.governanceMetricsCollector = governanceMetricsCollector;
        this.lingRepository = lingRepository;
        this.unloadCoordinator = unloadCoordinator;
        this.eventBus = eventBus;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ling-metrics-meter-bridge");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.lingHealthQps = MultiGauge.builder("lingframe.ling.health.qps").register(meterRegistry);
        this.lingHealthErrorRate = MultiGauge.builder("lingframe.ling.health.error_rate").register(meterRegistry);
        this.lingHealthP99Latency = MultiGauge.builder("lingframe.ling.health.p99_latency_ms").register(meterRegistry);
        this.lingHealthActiveRequests = MultiGauge.builder("lingframe.ling.health.active_requests").register(meterRegistry);
        this.versionHealthQps = MultiGauge.builder("lingframe.ling.version.health.qps").register(meterRegistry);
        this.versionHealthErrorRate = MultiGauge.builder("lingframe.ling.version.health.error_rate").register(meterRegistry);
        this.governanceRateLimited = MultiGauge.builder("lingframe.ling.governance.rate_limited_total").register(meterRegistry);
        this.governanceTimeouts = MultiGauge.builder("lingframe.ling.governance.timeout_total").register(meterRegistry);
        this.governanceCircuitOpened = MultiGauge.builder("lingframe.ling.governance.circuit_opened_total").register(meterRegistry);
        this.governanceCircuitRejected = MultiGauge.builder("lingframe.ling.governance.circuit_rejected_total").register(meterRegistry);
        this.governancePropagationSuccess = MultiGauge.builder("lingframe.ling.governance.transaction_propagation_success_total").register(meterRegistry);
        this.governancePropagationFailure = MultiGauge.builder("lingframe.ling.governance.transaction_propagation_failure_total").register(meterRegistry);
        this.activeLingCount = Gauge.builder("lingframe.ling.active_count", this, LingMetricsMeterBridge::activeLingCount).register(meterRegistry);
        // 卸载清理指标按粒度拆分：版本级卸载（滚动更新旧版本，高频短耗）与整灵元卸载（低频、清理范围更大）
        // 分开统计，避免混在一个指标里稀释耗时信号；累计计数统一 _total 后缀与既有指标风格一致
        this.versionUnloadCount = Gauge.builder("lingframe.ling.unload.version_count_total", this, LingMetricsMeterBridge::versionUnloadCount).register(meterRegistry);
        this.lingUnloadCount = Gauge.builder("lingframe.ling.unload.ling_count_total", this, LingMetricsMeterBridge::lingUnloadCount).register(meterRegistry);
        this.versionUnloadLastDurationMs = Gauge.builder("lingframe.ling.unload.version_last_duration_ms", this, LingMetricsMeterBridge::versionUnloadLastDurationMs).register(meterRegistry);
        this.lingUnloadLastDurationMs = Gauge.builder("lingframe.ling.unload.ling_last_duration_ms", this, LingMetricsMeterBridge::lingUnloadLastDurationMs).register(meterRegistry);
        this.leakAlertTotal = Gauge.builder("lingframe.ling.leak_alert_total", leakAlertCount, AtomicLong::get).register(meterRegistry);
    }

    private long activeLingCount() {
        return lingRepository == null ? 0 : lingRepository.getAllRuntimes().size();
    }

    private long versionUnloadCount() {
        return unloadCoordinator == null ? 0 : unloadCoordinator.getVersionUnloadCount();
    }

    private long lingUnloadCount() {
        return unloadCoordinator == null ? 0 : unloadCoordinator.getLingUnloadCount();
    }

    private long versionUnloadLastDurationMs() {
        return unloadCoordinator == null ? 0 : unloadCoordinator.getLastVersionUnloadDurationMs();
    }

    private long lingUnloadLastDurationMs() {
        return unloadCoordinator == null ? 0 : unloadCoordinator.getLastLingUnloadDurationMs();
    }

    @Override
    public void afterPropertiesSet() {
        // 泄漏告警计数：订阅泄漏检测事件，检测到泄漏（collected=true）时累加
        if (eventBus != null) {
            eventBus.subscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, leakListener);
        }
        refreshMeters();
        scheduler.scheduleAtFixedRate(this::refreshMeters, 5, 5, TimeUnit.SECONDS);
    }

    /** 泄漏检测事件监听器：仅统计「检测到泄漏（未被正常回收）」的事件，供告警大盘使用 */
    private final LingEventListener<MonitoringEvents.LeakDetectionEvent> leakListener = event -> {
        if (!event.isCollected()) {
            leakAlertCount.incrementAndGet();
        }
    };

    private void refreshMeters() {
        List<MultiGauge.Row<?>> summaryQpsRows = new ArrayList<>();
        List<MultiGauge.Row<?>> summaryErrorRows = new ArrayList<>();
        List<MultiGauge.Row<?>> summaryP99Rows = new ArrayList<>();
        List<MultiGauge.Row<?>> summaryActiveRows = new ArrayList<>();
        List<MultiGauge.Row<?>> versionQpsRows = new ArrayList<>();
        List<MultiGauge.Row<?>> versionErrorRows = new ArrayList<>();
        List<MultiGauge.Row<?>> governanceRateLimitedRows = new ArrayList<>();
        List<MultiGauge.Row<?>> governanceTimeoutRows = new ArrayList<>();
        List<MultiGauge.Row<?>> governanceCircuitOpenedRows = new ArrayList<>();
        List<MultiGauge.Row<?>> governanceCircuitRejectedRows = new ArrayList<>();
        List<MultiGauge.Row<?>> governancePropagationSuccessRows = new ArrayList<>();
        List<MultiGauge.Row<?>> governancePropagationFailureRows = new ArrayList<>();

        for (MetricsSnapshot snapshot : metricsCollector.getAllSnapshots()) {
            summaryQpsRows.add(MultiGauge.Row.of(Tags.of("ling_id", snapshot.getLingId()), snapshot.getQps()));
            summaryErrorRows.add(MultiGauge.Row.of(Tags.of("ling_id", snapshot.getLingId()), snapshot.getErrorRate()));
            summaryP99Rows.add(MultiGauge.Row.of(Tags.of("ling_id", snapshot.getLingId()), snapshot.getP99LatencyMs()));
            summaryActiveRows.add(MultiGauge.Row.of(Tags.of("ling_id", snapshot.getLingId()), snapshot.getActiveRequests()));

            for (MetricsSnapshot versionSnapshot : metricsCollector.getVersionSnapshots(snapshot.getLingId()).values()) {
                Tags tags = Tags.of("ling_id", versionSnapshot.getLingId(), "version", safe(versionSnapshot.getVersion()));
                versionQpsRows.add(MultiGauge.Row.of(tags, versionSnapshot.getQps()));
                versionErrorRows.add(MultiGauge.Row.of(tags, versionSnapshot.getErrorRate()));
            }
        }

        for (GovernanceMetricsSnapshot snapshot : governanceMetricsCollector.getAllSummaries().values()) {
            Tags lingTags = Tags.of("ling_id", snapshot.getLingId());
            governanceRateLimitedRows.add(MultiGauge.Row.of(lingTags, snapshot.getRateLimitedRequests()));
            governanceTimeoutRows.add(MultiGauge.Row.of(lingTags, snapshot.getTimeoutRequests()));
            governanceCircuitOpenedRows.add(MultiGauge.Row.of(lingTags, snapshot.getCircuitOpenedCount()));
            governanceCircuitRejectedRows.add(MultiGauge.Row.of(lingTags, snapshot.getCircuitOpenRejections()));
            governancePropagationSuccessRows.add(MultiGauge.Row.of(lingTags, snapshot.getTransactionPropagationSuccessCount()));
            governancePropagationFailureRows.add(MultiGauge.Row.of(lingTags, snapshot.getTransactionPropagationFailureCount()));

            Map<String, GovernanceMetricsSnapshot> versions = governanceMetricsCollector.getVersionSnapshots(snapshot.getLingId());
            for (GovernanceMetricsSnapshot versionSnapshot : versions.values()) {
                Tags versionTags = Tags.of("ling_id", versionSnapshot.getLingId(), "version", safe(versionSnapshot.getVersion()));
                governanceRateLimitedRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getRateLimitedRequests()));
                governanceTimeoutRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getTimeoutRequests()));
                governanceCircuitOpenedRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getCircuitOpenedCount()));
                governanceCircuitRejectedRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getCircuitOpenRejections()));
                governancePropagationSuccessRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getTransactionPropagationSuccessCount()));
                governancePropagationFailureRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getTransactionPropagationFailureCount()));
            }
        }

        lingHealthQps.register(summaryQpsRows, true);
        lingHealthErrorRate.register(summaryErrorRows, true);
        lingHealthP99Latency.register(summaryP99Rows, true);
        lingHealthActiveRequests.register(summaryActiveRows, true);
        versionHealthQps.register(versionQpsRows, true);
        versionHealthErrorRate.register(versionErrorRows, true);
        governanceRateLimited.register(governanceRateLimitedRows, true);
        governanceTimeouts.register(governanceTimeoutRows, true);
        governanceCircuitOpened.register(governanceCircuitOpenedRows, true);
        governanceCircuitRejected.register(governanceCircuitRejectedRows, true);
        governancePropagationSuccess.register(governancePropagationSuccessRows, true);
        governancePropagationFailure.register(governancePropagationFailureRows, true);
    }

    private String safe(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    @Override
    public void destroy() {
        if (eventBus != null) {
            eventBus.unsubscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, leakListener);
        }
        scheduler.shutdownNow();
    }
}
