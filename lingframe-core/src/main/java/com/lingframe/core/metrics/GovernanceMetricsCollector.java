package com.lingframe.core.metrics;

import com.lingframe.core.spi.LingGovernanceMetricsCollector;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class GovernanceMetricsCollector implements LingGovernanceMetricsCollector {

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

    @Override
    public void recordForceDrain(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementForceDrainCount);
    }

    @Override
    public void recordDrainTimeoutAbort(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementDrainTimeoutAbortCount);
    }

    public void recordRecovered(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementRecoveryCount);
    }

    /**
     * 记录穿透连接被废弃（poisoned）次数：超时/放弃执行后宽限期未退出，
     * 跳过 rollback 直接 close 废弃该池连接（未提交写随 close 丢弃）。
     *
     * @param lingId  灵元 ID
     * @param version 目标版本（可为 null，仅计入汇总桶）
     */
    public void recordConnectionPoisoned(String lingId, String version) {
        mutate(lingId, version, GovernanceMetricBucket::incrementConnectionPoisonedCount);
    }

    /**
     * 记录一次事务穿透结果（成功 = 根事务正常提交/回滚链完整，失败 = rollbackOnly 信号上行触发回滚）。
     *
     * @param lingId  灵元 ID
     * @param version 版本
     * @param success 穿透是否成功
     */
    public void recordTransactionPropagation(String lingId, String version, boolean success) {
        mutate(lingId, version, bucket -> bucket.recordTransactionPropagation(success));
    }

    public void recordThreadBudgetSnapshot(String lingId, String version, int activeThreads, int maxThreads) {
        mutate(lingId, version, bucket -> bucket.recordThreadBudgetSnapshot(activeThreads, maxThreads));
    }

    public void recordCpuBudgetObservation(String lingId, String version, long cpuTimeMs, Integer cpuBudgetMsPerMinute) {
        mutate(lingId, version, bucket -> bucket.recordCpuBudgetObservation(cpuTimeMs, cpuBudgetMsPerMinute));
    }

    public void recordMemoryBudgetObservation(String lingId, String version, long estimatedHeapDeltaBytes, Integer memoryBudgetMb) {
        mutate(lingId, version, bucket -> bucket.recordMemoryBudgetObservation(estimatedHeapDeltaBytes, memoryBudgetMb));
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
            snapshot.setForceDrainCount(snapshot.getForceDrainCount() + versionSnapshot.getForceDrainCount());
            snapshot.setDrainTimeoutAbortCount(snapshot.getDrainTimeoutAbortCount() + versionSnapshot.getDrainTimeoutAbortCount());
            snapshot.setRecoveryCount(snapshot.getRecoveryCount() + versionSnapshot.getRecoveryCount());
            snapshot.setConnectionPoisonedCount(snapshot.getConnectionPoisonedCount() + versionSnapshot.getConnectionPoisonedCount());
            snapshot.setTransactionPropagationSuccessCount(snapshot.getTransactionPropagationSuccessCount() + versionSnapshot.getTransactionPropagationSuccessCount());
            snapshot.setTransactionPropagationFailureCount(snapshot.getTransactionPropagationFailureCount() + versionSnapshot.getTransactionPropagationFailureCount());
            snapshot.setActiveIsolatedThreads(snapshot.getActiveIsolatedThreads() + versionSnapshot.getActiveIsolatedThreads());
            snapshot.setMaxConcurrentThreadsBudget(snapshot.getMaxConcurrentThreadsBudget() + versionSnapshot.getMaxConcurrentThreadsBudget());
            snapshot.setThreadBudgetExceededCount(snapshot.getThreadBudgetExceededCount() + versionSnapshot.getThreadBudgetExceededCount());
            snapshot.setCpuTimeMsLastMinute(snapshot.getCpuTimeMsLastMinute() + versionSnapshot.getCpuTimeMsLastMinute());
            snapshot.setCpuBudgetExceededCount(snapshot.getCpuBudgetExceededCount() + versionSnapshot.getCpuBudgetExceededCount());
            snapshot.setEstimatedHeapDeltaBytes(Math.max(snapshot.getEstimatedHeapDeltaBytes(), versionSnapshot.getEstimatedHeapDeltaBytes()));
            snapshot.setMemoryBudgetExceededCount(snapshot.getMemoryBudgetExceededCount() + versionSnapshot.getMemoryBudgetExceededCount());
            // 预算上限字段应取最大值而非求和：上限是多版本共享的容量边界，
            // 求和会得出一个不存在意义的加和值，误判预算超支。
            // 与同方法 estimatedHeapDeltaBytes 取 max 的语义对齐。
            snapshot.setCpuBudgetMsPerMinute(maxNullable(snapshot.getCpuBudgetMsPerMinute(), versionSnapshot.getCpuBudgetMsPerMinute()));
            snapshot.setMemoryBudgetMb(maxNullable(snapshot.getMemoryBudgetMb(), versionSnapshot.getMemoryBudgetMb()));
            timestamp = Math.max(timestamp, versionSnapshot.getTimestamp());
        }
        snapshot.setTimestamp(timestamp > 0 ? timestamp : System.currentTimeMillis());
        return snapshot;
    }

    private Integer maxNullable(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
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
        private final LongAdder forceDrainCount = new LongAdder();
        private final LongAdder drainTimeoutAbortCount = new LongAdder();
        private final LongAdder recoveryCount = new LongAdder();
        private final LongAdder connectionPoisonedCount = new LongAdder();
        private final LongAdder transactionPropagationSuccessCount = new LongAdder();
        private final LongAdder transactionPropagationFailureCount = new LongAdder();
        private final LongAdder threadBudgetExceededCount = new LongAdder();
        private final LongAdder cpuBudgetExceededCount = new LongAdder();
        private final LongAdder memoryBudgetExceededCount = new LongAdder();
        private volatile int activeIsolatedThreads;
        private volatile int maxConcurrentThreadsBudget;
        private volatile long cpuTimeWindowStart = System.currentTimeMillis();
        private volatile long cpuTimeMsLastMinute;
        private volatile Integer cpuBudgetMsPerMinute;
        private volatile long estimatedHeapDeltaBytes;
        private volatile Integer memoryBudgetMb;
        private volatile boolean cpuBudgetCurrentlyExceeded;
        private volatile boolean memoryBudgetCurrentlyExceeded;
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
            threadBudgetExceededCount.increment();
            touch();
        }

        private void incrementForceDrainCount() {
            forceDrainCount.increment();
            touch();
        }

        private void incrementDrainTimeoutAbortCount() {
            drainTimeoutAbortCount.increment();
            touch();
        }

        private void incrementRecoveryCount() {
            recoveryCount.increment();
            touch();
        }

        private void incrementConnectionPoisonedCount() {
            connectionPoisonedCount.increment();
            touch();
        }

        private void recordTransactionPropagation(boolean success) {
            if (success) {
                transactionPropagationSuccessCount.increment();
            } else {
                transactionPropagationFailureCount.increment();
            }
            touch();
        }

        private void recordThreadBudgetSnapshot(int activeThreads, int maxThreads) {
            this.activeIsolatedThreads = Math.max(0, activeThreads);
            this.maxConcurrentThreadsBudget = Math.max(0, maxThreads);
            touch();
        }

        private synchronized void recordCpuBudgetObservation(long cpuTimeMs, Integer budgetMsPerMinute) {
            long now = System.currentTimeMillis();
            if (now - cpuTimeWindowStart >= 60_000L) {
                cpuTimeWindowStart = now;
                cpuTimeMsLastMinute = 0L;
                cpuBudgetCurrentlyExceeded = false;
            }
            cpuTimeMsLastMinute += Math.max(0L, cpuTimeMs);
            cpuBudgetMsPerMinute = budgetMsPerMinute;
            boolean exceeded = budgetMsPerMinute != null && budgetMsPerMinute > 0 && cpuTimeMsLastMinute > budgetMsPerMinute;
            if (exceeded && !cpuBudgetCurrentlyExceeded) {
                cpuBudgetExceededCount.increment();
            }
            cpuBudgetCurrentlyExceeded = exceeded;
            touch();
        }

        private void recordMemoryBudgetObservation(long heapDeltaBytes, Integer budgetMb) {
            estimatedHeapDeltaBytes = Math.max(estimatedHeapDeltaBytes, Math.max(0L, heapDeltaBytes));
            memoryBudgetMb = budgetMb;
            boolean exceeded = budgetMb != null && budgetMb > 0 && estimatedHeapDeltaBytes > budgetMb * 1024L * 1024L;
            if (exceeded && !memoryBudgetCurrentlyExceeded) {
                memoryBudgetExceededCount.increment();
            }
            memoryBudgetCurrentlyExceeded = exceeded;
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
            snapshot.setForceDrainCount(forceDrainCount.sum());
            snapshot.setDrainTimeoutAbortCount(drainTimeoutAbortCount.sum());
            snapshot.setRecoveryCount(recoveryCount.sum());
            snapshot.setConnectionPoisonedCount(connectionPoisonedCount.sum());
            snapshot.setTransactionPropagationSuccessCount(transactionPropagationSuccessCount.sum());
            snapshot.setTransactionPropagationFailureCount(transactionPropagationFailureCount.sum());
            snapshot.setActiveIsolatedThreads(activeIsolatedThreads);
            snapshot.setMaxConcurrentThreadsBudget(maxConcurrentThreadsBudget);
            snapshot.setThreadBudgetExceededCount(threadBudgetExceededCount.sum());
            snapshot.setCpuTimeMsLastMinute(cpuTimeMsLastMinute);
            snapshot.setCpuBudgetMsPerMinute(cpuBudgetMsPerMinute);
            snapshot.setCpuBudgetExceededCount(cpuBudgetExceededCount.sum());
            snapshot.setEstimatedHeapDeltaBytes(estimatedHeapDeltaBytes);
            snapshot.setMemoryBudgetMb(memoryBudgetMb);
            snapshot.setMemoryBudgetExceededCount(memoryBudgetExceededCount.sum());
            snapshot.setTimestamp(timestamp);
            return snapshot;
        }
    }
}
