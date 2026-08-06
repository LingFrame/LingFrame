package com.lingframe.dashboard.controller;

import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.classloader.LingClassLoader;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.JVMMetrics;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.core.metrics.UnifiedMetrics;
import com.lingframe.core.spi.ThreadPoolStatsProvider;
import com.lingframe.dashboard.dto.*;
import com.lingframe.dashboard.service.LeakDetectionCacheService;
import com.lingframe.dashboard.service.LingResourceMetricsCollector;
import com.lingframe.dashboard.service.MetricsAggregationService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JVM 和灵元运行时指标控制器。
 * 职责：JVM 指标、灵元健康、运行时诊断、泄漏检测、线程池、
 *       灵元资源占用、事件管道指标。
 * 从 LingController 中拆分，专注指标监控维度。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/metrics")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsCollector metricsCollector;
    private final GovernanceMetricsCollector governanceMetricsCollector;
    private final RuntimeDiagnosticsService runtimeDiagnosticsService;
    private final LeakDetectionCacheService leakDetectionCacheService;
    private final LingResourceMetricsCollector lingResourceMetricsCollector;
    private final ThreadPoolStatsProvider threadPoolStatsProvider;
    private final EventBus eventBus;
    private final MetricsAggregationService metricsAggregationService;

    /**
     * 获取 JVM 综合性能指标（CPU / 内存 / 堆 / Metaspace / 类加载 / 线程 / GC / 系统信息）
     */
    @GetMapping("/jvm")
    public ApiResponse<JvmMetricsDTO> getJvmMetrics() {
        try {
            return ApiResponse.ok(assembleJvmMetrics());
        } catch (Exception e) {
            log.error("Failed to get JVM metrics", e);
            return ApiResponse.error("获取性能指标失败: " + e.getMessage());
        }
    }

    /**
     * 获取单个灵元健康指标
     */
    @GetMapping("/lings/{lingId}/health")
    public ApiResponse<MetricsSnapshot> getLingHealth(@PathVariable String lingId) {
        try {
            return ApiResponse.ok(metricsCollector.getSnapshot(lingId));
        } catch (Exception e) {
            log.error("Failed to get health metrics for ling: {}", lingId, e);
            return ApiResponse.error("获取健康指标失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有灵元的健康指标总览（摘要 + 版本明细）
     */
    @GetMapping("/lings/health/all")
    public ApiResponse<Map<String, LingHealthViewDTO>> getAllLingHealth() {
        try {
            return ApiResponse.ok(metricsAggregationService.getAllHealthView());
        } catch (Exception e) {
            log.error("Failed to get all health metrics", e);
            return ApiResponse.error("获取健康指标失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有灵元的治理指标总览（摘要 + 版本明细）
     */
    @GetMapping("/lings/governance/all")
    public ApiResponse<Map<String, LingGovernanceMetricsViewDTO>> getAllLingGovernanceMetrics() {
        try {
            return ApiResponse.ok(metricsAggregationService.getAllGovernanceView());
        } catch (Exception e) {
            log.error("Failed to get governance metrics", e);
            return ApiResponse.error("获取治理指标失败: " + e.getMessage());
        }
    }

    /**
     * 获取运行时诊断（资源清理能力快照）
     */
    @GetMapping("/runtime-diagnostics")
    public ApiResponse<Map<String, ResourceCleanupCapabilityDTO>> getRuntimeDiagnostics() {
        try {
            return ApiResponse.ok(runtimeDiagnosticsService.getCleanupCapabilities());
        } catch (Exception e) {
            log.error("Failed to get runtime diagnostics", e);
            return ApiResponse.error("获取运行时诊断失败: " + e.getMessage());
        }
    }

    /**
     * 获取运行时治理就绪度评估
     */
    @GetMapping("/runtime-governance-readiness")
    public ApiResponse<RuntimeGovernanceReadinessDTO> getRuntimeGovernanceReadiness() {
        try {
            return ApiResponse.ok(runtimeDiagnosticsService.getGovernanceReadiness());
        } catch (Exception e) {
            log.error("Failed to get runtime governance readiness", e);
            return ApiResponse.error("获取运行时治理就绪度失败: " + e.getMessage());
        }
    }

    /**
     * 获取泄漏检测记录（未回收的置顶）
     */
    @GetMapping("/leak-detections")
    public ApiResponse<List<LeakDetectionRecordDTO>> getLeakDetections() {
        return ApiResponse.ok(leakDetectionCacheService.getRecords());
    }

    /**
     * 获取各灵元隔离线程池状态
     */
    @GetMapping("/thread-pools")
    public ApiResponse<List<ThreadPoolStatsDTO>> getThreadPoolStats() {
        List<ThreadPoolStatsDTO> result = threadPoolStatsProvider.getThreadPoolStats().stream()
                .map(s -> ThreadPoolStatsDTO.builder()
                        .lingId(s.getLingId())
                        .activeCount(s.getActiveCount())
                        .poolSize(s.getPoolSize())
                        .maxThreads(s.getMaxThreads())
                        .queueSize(s.getQueueSize())
                        .completedTaskCount(s.getCompletedTaskCount())
                        .build())
                .collect(Collectors.toList());
        return ApiResponse.ok(result);
    }

    /**
     * 获取各灵元资源占用指标（类数 / 线程数 / CPU / 堆增量 / Metaspace 估算）
     */
    @GetMapping("/per-ling")
    public ApiResponse<List<LingResourceMetricsDTO>> getPerLingMetrics() {
        return ApiResponse.ok(lingResourceMetricsCollector.getMetrics());
    }

    /**
     * 获取 EventBus 和 AuditManager 的内部指标（事件管道健康度）
     */
    @GetMapping("/event-pipeline")
    public ApiResponse<Map<String, Object>> getEventPipelineMetrics() {
        try {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("eventBusDroppedCount", eventBus.getDroppedAsyncEvents());
            metrics.put("eventBusSubmittedCount", eventBus.getSubmittedAsyncEvents());
            metrics.put("eventBusQueueSize", eventBus.getQueueSize());
            metrics.put("eventBusQueueRemainingCapacity", eventBus.getQueueRemainingCapacity());
            metrics.put("eventBusOverflowPolicy", eventBus.getOverflowPolicy().name());
            metrics.put("auditDiscardCount", AuditManager.getDiscardCount());
            metrics.put("auditOverflowPolicy", AuditManager.getOverflowPolicy().name());
            metrics.put("auditShutdown", AuditManager.isShutdown());
            return ApiResponse.ok(metrics);
        } catch (Exception e) {
            log.error("Failed to get event pipeline metrics", e);
            return ApiResponse.error("获取事件管道指标失败: " + e.getMessage());
        }
    }

    /**
     * 获取灵核进程级整体健康快照（JVM + 系统 + 所有灵元指标聚合）。
     * <p>
     * 一次给出「灵核 + 所有灵元 + JVM」的整体运行时画像，
     * 供外部监控一次性拉取进程级健康视图，无需分别调多个专项端点再聚合。
     */
    @GetMapping("/unified")
    public ApiResponse<UnifiedMetrics> getUnifiedMetrics() {
        try {
            return ApiResponse.ok(UnifiedMetrics.create(metricsCollector));
        } catch (Exception e) {
            log.error("Failed to get unified metrics", e);
            return ApiResponse.error("获取整体健康快照失败: " + e.getMessage());
        }
    }

    /**
     * 组装 JVM 指标 DTO（从 JVMMetrics 和系统属性中提取所有字段）
     */
    private JvmMetricsDTO assembleJvmMetrics() {
        JVMMetrics jvm = JVMMetrics.collect();
        return JvmMetricsDTO.builder()
                // CPU
                .cpuUsage(jvm.getCpuUsage())
                .processCpuLoad(jvm.getProcessCpuLoad())
                // 内存
                .memoryUsedMB(jvm.getUsedMemoryMB())
                .memoryTotalMB(jvm.getTotalMemoryMB())
                .memoryUsage(jvm.getMemoryUsagePercent())
                // 堆
                .heapUsedMB(jvm.getHeapUsedMB())
                .heapMaxMB(jvm.getHeapMaxMB())
                .heapUsage(jvm.getHeapUsagePercent())
                // Metaspace
                .metaspaceUsedKB(jvm.getMetaspaceUsedKB())
                .metaspaceMaxKB(jvm.getMetaspaceMaxKB())
                .metaspaceUsage(jvm.getMetaspaceUsagePercent())
                // 类加载
                .loadedClassCount(jvm.getLoadedClassCount())
                .totalLoadedClassCount(jvm.getTotalLoadedClassCount())
                .unloadedClassCount(jvm.getUnloadedClassCount())
                .lingClassLoaderCount(LingClassLoader.getAliveCount())
                // 线程
                .threadCount(jvm.getThreadCount())
                .daemonThreadCount(jvm.getDaemonThreadCount())
                .peakThreadCount(jvm.getPeakThreadCount())
                // GC
                .gcCount(jvm.getGcCount())
                .gcTimeMs(jvm.getGcTimeMs())
                .gcDetails(jvm.getGcDetails())
                // 系统信息
                .availableProcessors(jvm.getAvailableProcessors())
                .systemLoadAverage(jvm.getSystemLoadAverage())
                .jvmVersion(System.getProperty("java.version", ""))
                .jvmVendor(System.getProperty("java.vendor", ""))
                .osName(System.getProperty("os.name", ""))
                .osArch(System.getProperty("os.arch", ""))
                .uptimeMs(ManagementFactory.getRuntimeMXBean().getUptime())
                .pid(getProcessId())
                .build();
    }

    private static String getProcessId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
    }
}
