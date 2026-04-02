package com.lingframe.core.metrics;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class GovernanceMetricsCollector {

    private final Map<String, GovernanceMetricBucket> summaryBuckets = new ConcurrentHashMap<>();
    private final Map<String, GovernanceMetricBucket> versionBuckets = new ConcurrentHashMap<>();

    public void recordRateLimited(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementRateLimitedRequests);
    }

    public void recordTimeout(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementTimeoutRequests);
    }

    public void recordCircuitOpenRejected(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementCircuitOpenRejections);
    }

    public void recordCircuitOpened(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementCircuitOpenedCount);
    }

    public void recordBulkheadRejected(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementBulkheadRejectedRequests);
    }

    public void recordRecovered(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementRecoveryCount);
    }

    public GovernanceMetricsSnapshot getSummary(String lingId) {
        GovernanceMetricBucket bucket = summaryBuckets.get(lingId);
        if (bucket != null) {
            return bucket.snapshot();
        }
        Map<String, GovernanceMetricsSnapshot> versions = getVersionSnapshots(lingId);
        if (!versions.isEmpty()) {
            return aggregate(lingId, versions);
        }
        return GovernanceMetricsSnapshot.empty(lingId);
    }

    public Map<String, GovernanceMetricsSnapshot> getVersionSnapshots(String lingId) {
        return versionBuckets.entrySet().stream()
                .filter(entry -> lingId.equals(extractLingId(entry.getKey())))
                .collect(Collectors.toMap(
                        entry -> entry.getValue().getVersion(),
                        entry -> entry.getValue().snapshot(),
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));
    }

    public Map<String, GovernanceMetricsSnapshot> getAllSummaries() {
        return summaryBuckets.keySet().stream()
                .collect(Collectors.toMap(
                        lingId -> lingId,
                        this::getSummary,
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));
    }

    public void remove(String lingId) {
        summaryBuckets.remove(lingId);
        versionBuckets.keySet().removeIf(key -> lingId.equals(extractLingId(key)));
    }

    private void mutate(String lingId, String version, Consumer<GovernanceMetricBucket> mutation) {
        if (lingId == null || lingId.isEmpty()) {
            return;
        }
        GovernanceMetricBucket summary = summaryBuckets.computeIfAbsent(lingId, GovernanceMetricBucket::new);
        mutation.accept(summary);
        if (version != null && !version.isEmpty()) {
            GovernanceMetricBucket versionBucket = versionBuckets.computeIfAbsent(versionKey(lingId, version),
                    key -> new GovernanceMetricBucket(lingId, version));
            mutation.accept(versionBucket);
        }
    }

    private GovernanceMetricsSnapshot aggregate(String lingId, Map<String, GovernanceMetricsSnapshot> versions) {
        GovernanceMetricsSnapshot snapshot = GovernanceMetricsSnapshot.empty(lingId);
        long timestamp = 0L;
        for (GovernanceMetricsSnapshot versionSnapshot : versions.values()) {
            snapshot.setRateLimitedRequests(snapshot.getRateLimitedRequests() + versionSnapshot.getRateLimitedRequests());
            snapshot.setTimeoutRequests(snapshot.getTimeoutRequests() + versionSnapshot.getTimeoutRequests());
            snapshot.setCircuitOpenRejections(snapshot.getCircuitOpenRejections() + versionSnapshot.getCircuitOpenRejections());
            snapshot.setCircuitOpenedCount(snapshot.getCircuitOpenedCount() + versionSnapshot.getCircuitOpenedCount());
            snapshot.setBulkheadRejectedRequests(snapshot.getBulkheadRejectedRequests() + versionSnapshot.getBulkheadRejectedRequests());
            snapshot.setRecoveryCount(snapshot.getRecoveryCount() + versionSnapshot.getRecoveryCount());
            timestamp = Math.max(timestamp, versionSnapshot.getTimestamp());
        }
        snapshot.setTimestamp(timestamp > 0 ? timestamp : System.currentTimeMillis());
        return snapshot;
    }

    private String versionKey(String lingId, String version) {
        return lingId + "::" + version;
    }

    private String extractLingId(String key) {
        int separator = key.indexOf("::");
        return separator >= 0 ? key.substring(0, separator) : key;
    }

    @Getter
    private static final class GovernanceMetricBucket {
        private final String lingId;
        private final String version;
        private final LongAdder rateLimitedRequests = new LongAdder();
        private final LongAdder timeoutRequests = new LongAdder();
        private final LongAdder circuitOpenRejections = new LongAdder();
        private final LongAdder circuitOpenedCount = new LongAdder();
        private final LongAdder bulkheadRejectedRequests = new LongAdder();
        private final LongAdder recoveryCount = new LongAdder();
        private volatile long timestamp = System.currentTimeMillis();

        private GovernanceMetricBucket(String lingId) {
            this(lingId, null);
        }

        private GovernanceMetricBucket(String lingId, String version) {
            this.lingId = lingId;
            this.version = version;
        }

        private void incrementRateLimitedRequests() {
            rateLimitedRequests.increment();
            touch();
        }

        private void incrementTimeoutRequests() {
            timeoutRequests.increment();
            touch();
        }

        private void incrementCircuitOpenRejections() {
            circuitOpenRejections.increment();
            touch();
        }

        private void incrementCircuitOpenedCount() {
            circuitOpenedCount.increment();
            touch();
        }

        private void incrementBulkheadRejectedRequests() {
            bulkheadRejectedRequests.increment();
            touch();
        }

        private void incrementRecoveryCount() {
            recoveryCount.increment();
            touch();
        }

        private void touch() {
            timestamp = System.currentTimeMillis();
        }

        private GovernanceMetricsSnapshot snapshot() {
            GovernanceMetricsSnapshot snapshot = new GovernanceMetricsSnapshot();
            snapshot.setLingId(lingId);
            snapshot.setVersion(version);
            snapshot.setRateLimitedRequests(rateLimitedRequests.sum());
            snapshot.setTimeoutRequests(timeoutRequests.sum());
            snapshot.setCircuitOpenRejections(circuitOpenRejections.sum());
            snapshot.setCircuitOpenedCount(circuitOpenedCount.sum());
            snapshot.setBulkheadRejectedRequests(bulkheadRejectedRequests.sum());
            snapshot.setRecoveryCount(recoveryCount.sum());
            snapshot.setTimestamp(timestamp);
            return snapshot;
        }
    }
}
