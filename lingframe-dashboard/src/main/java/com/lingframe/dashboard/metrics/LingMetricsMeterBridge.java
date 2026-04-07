package com.lingframe.dashboard.metrics;

import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.GovernanceMetricsSnapshot;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
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

public class LingMetricsMeterBridge implements InitializingBean, DisposableBean {

    private final MetricsCollector metricsCollector;
    private final GovernanceMetricsCollector governanceMetricsCollector;
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

    public LingMetricsMeterBridge(MeterRegistry meterRegistry,
                                  MetricsCollector metricsCollector,
                                  GovernanceMetricsCollector governanceMetricsCollector) {
        this.metricsCollector = metricsCollector;
        this.governanceMetricsCollector = governanceMetricsCollector;
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
    }

    @Override
    public void afterPropertiesSet() {
        refreshMeters();
        scheduler.scheduleAtFixedRate(this::refreshMeters, 5, 5, TimeUnit.SECONDS);
    }

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

            Map<String, GovernanceMetricsSnapshot> versions = governanceMetricsCollector.getVersionSnapshots(snapshot.getLingId());
            for (GovernanceMetricsSnapshot versionSnapshot : versions.values()) {
                Tags versionTags = Tags.of("ling_id", versionSnapshot.getLingId(), "version", safe(versionSnapshot.getVersion()));
                governanceRateLimitedRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getRateLimitedRequests()));
                governanceTimeoutRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getTimeoutRequests()));
                governanceCircuitOpenedRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getCircuitOpenedCount()));
                governanceCircuitRejectedRows.add(MultiGauge.Row.of(versionTags, versionSnapshot.getCircuitOpenRejections()));
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
    }

    private String safe(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
