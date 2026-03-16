package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.dashboard.dto.*;
import com.lingframe.dashboard.service.DashboardService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.lang.management.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 灵元治理仪表盘控制器
 * 提供灵元安装、状态管理、权限审阅及流量监控等功能的 REST 接口。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/lings")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class LingController {

    // CPU 计算用的缓存变量
    private final AtomicLong lastCpuTime = new AtomicLong(0);
    private final AtomicLong lastSampleTime = new AtomicLong(0);

    private final LingFrameConfig lingFrameConfig;

    private final DashboardService dashboardService;
    private final boolean installEnabled;

    public LingController(LingFrameConfig lingFrameConfig,
            DashboardService dashboardService,
            @Value("${lingframe.dashboard.install-enabled:false}") boolean installEnabled) {
        this.lingFrameConfig = lingFrameConfig;
        this.dashboardService = dashboardService;
        this.installEnabled = installEnabled;
    }

    /**
     * 获取所有灵元的运行快照列表
     */
    @GetMapping
    public ApiResponse<List<LingInfoDTO>> listLings() {
        try {
            return ApiResponse.ok(dashboardService.getAllLingInfos());
        } catch (Exception e) {
            log.error("Failed to list Lings", e);
            return ApiResponse.error("获取灵元列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定灵元的详细信息
     */
    @GetMapping("/{lingId}")
    public ApiResponse<LingInfoDTO> getLing(@PathVariable String lingId) {
        try {
            LingInfoDTO info = dashboardService.getLingInfo(lingId);
            if (info == null) {
                return ApiResponse.error("灵元不存在: " + lingId);
            }
            return ApiResponse.ok(info);
        } catch (Exception e) {
            log.error("Failed to get ling: {}", lingId, e);
            return ApiResponse.error("获取灵元失败: " + e.getMessage());
        }
    }

    /**
     * 更新灵元的运行时状态（激活、撤权等）
     */
    @PostMapping("/{lingId}/status")
    public ApiResponse<LingInfoDTO> updateStatus(
            @PathVariable String lingId,
            @RequestBody LingStatusRequest request) {
        try {
            LingInfoDTO info = dashboardService.updateStatus(lingId, request.getStatus(), request.getVersion());
            return ApiResponse.ok("状态已更新", info);
        } catch (Exception e) {
            log.error("Failed to update status: {}", lingId, e);
            return ApiResponse.error("状态更新失败: " + e.getMessage());
        }
    }

    /**
     * 安装或更新灵元
     * 通过上传灵元 JAR 包进行静态解析并执行冷启动部署。
     */
    @PostMapping("/install")
    public ApiResponse<LingInfoDTO> install(@RequestParam("file") MultipartFile file) {
        try {
            if (!installEnabled) {
                return ApiResponse.error("安装接口未启用，请设置 lingframe.dashboard.install-enabled=true");
            }
            if (file.isEmpty()) {
                return ApiResponse.error("文件为空");
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !originalFilename.endsWith(".jar")) {
                return ApiResponse.error("文件必须是 JAR 包");
            }

            // 保存文件
            File lingDir = new File(lingFrameConfig.getLingHome()).getAbsoluteFile();
            if (!lingDir.exists())
                lingDir.mkdirs();
            File targetFile = new File(lingDir, originalFilename);
            log.info("Saving uploaded ling to: {}", targetFile.getAbsolutePath());
            file.transferTo(targetFile);
            // 安装灵元
            LingInfoDTO info = dashboardService.installLing(targetFile);

            return ApiResponse.ok("安装成功", info);
        } catch (Exception e) {
            log.error("Install failed", e);
            return ApiResponse.error("安装失败: " + e.getMessage());
        }
    }

    /**
     * 卸载灵元
     * 彻底回收灵元占用的 ClassLoader 及相关资源。
     */
    @DeleteMapping("/uninstall/{lingId}")
    public ApiResponse<Void> uninstall(@PathVariable String lingId) {
        try {
            dashboardService.uninstallLing(lingId);
            return ApiResponse.ok("卸载成功", null);
        } catch (Exception e) {
            log.error("Uninstall failed: {}", lingId, e);
            return ApiResponse.error("卸载失败: " + e.getMessage());
        }
    }

    /**
     * 按版本卸载灵元
     */
    @DeleteMapping("/uninstall/{lingId}/{version}")
    public ApiResponse<Void> uninstallVersion(@PathVariable String lingId, @PathVariable String version) {
        try {
            dashboardService.uninstallLing(lingId, version);
            return ApiResponse.ok("版本 " + version + " 卸载成功", null);
        } catch (Exception e) {
            log.error("Uninstall failed for: {}:{}", lingId, version, e);
            return ApiResponse.error("卸载特定版本失败: " + e.getMessage());
        }
    }

    /**
     * 热重载灵元
     * 仅在开发模式下允许，用于快速应用代码变更。
     */
    @PostMapping("/{lingId}/reload")
    public ApiResponse<LingInfoDTO> reload(
            @PathVariable String lingId,
            @RequestBody(required = false) LingReloadRequest request) {
        if (!lingFrameConfig.isDevMode()) {
            return ApiResponse.error("热重载仅在开发模式下可用");
        }
        try {
            String version = request != null ? request.getVersion() : null;
            LingInfoDTO info = dashboardService.reloadLing(lingId, version);
            return ApiResponse.ok("重载成功", info);
        } catch (Exception e) {
            log.error("Reload failed: {}", lingId, e);
            return ApiResponse.error("重载失败: " + e.getMessage());
        }
    }

    @PostMapping("/{lingId}/canary")
    public ApiResponse<Void> setCanary(
            @PathVariable String lingId,
            @RequestBody CanaryConfigDTO request) {
        try {
            dashboardService.setCanaryConfig(lingId, request.getPercent(), request.getCanaryVersion());
            return ApiResponse.ok("灰度配置已更新", null);
        } catch (Exception e) {
            log.error("Failed to set canary: {}", lingId, e);
            return ApiResponse.error("灰度配置失败: " + e.getMessage());
        }
    }

    @GetMapping("/{lingId}/stats")
    public ApiResponse<TrafficStatsDTO> getStats(@PathVariable String lingId) {
        try {
            return ApiResponse.ok(dashboardService.getTrafficStats(lingId));
        } catch (Exception e) {
            log.error("Failed to get stats: {}", lingId, e);
            return ApiResponse.error("获取统计失败: " + e.getMessage());
        }
    }

    @PostMapping("/{lingId}/stats/reset")
    public ApiResponse<Void> resetStats(@PathVariable String lingId) {
        try {
            dashboardService.resetTrafficStats(lingId);
            return ApiResponse.ok("统计已重置", null);
        } catch (Exception e) {
            log.error("Failed to reset stats: {}", lingId, e);
            return ApiResponse.error("重置失败: " + e.getMessage());
        }
    }

    /**
     * 获取 JVM 性能指标
     */
    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> getMetrics() {
        try {
            Map<String, Object> metrics = new HashMap<>();

            // CPU 使用率（通过 Process CPU 时间计算）
            int cpuUsage = calculateCpuUsage();
            metrics.put("cpuUsage", cpuUsage);

            // 内存信息 (JVM 堆内存)
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();  // 当前分配的内存
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory; // 已使用的内存

            metrics.put("memoryUsedMB", usedMemory / 1024 / 1024);
            metrics.put("memoryTotalMB", totalMemory / 1024 / 1024);
            metrics.put("memoryUsage", Math.round((double) usedMemory / totalMemory * 100)); // 使用当前分配内存计算百分比

            // 堆内存（通过 MemoryMXBean）
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            metrics.put("heapUsedMB", heapUsage.getUsed() / 1024 / 1024);
            metrics.put("heapMaxMB", heapUsage.getMax() / 1024 / 1024);

            // 线程数
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            metrics.put("threadCount", threadMXBean.getThreadCount());

            // GC 次数
            long gcCount = 0;
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCount += gcBean.getCollectionCount();
            }
            metrics.put("gcCount", gcCount);

            return ApiResponse.ok(metrics);
        } catch (Exception e) {
            log.error("Failed to get metrics", e);
            return ApiResponse.error("获取性能指标失败: " + e.getMessage());
        }
    }

    /**
     * 计算 JVM 进程 CPU 使用率
     * 使用 ThreadMXBean 统计所有线程的 CPU 时间
     */
    private int calculateCpuUsage() {
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            long[] threadIds = threadBean.getAllThreadIds();
            long totalCpuTime = 0;

            // 获取每个线程的 CPU 时间
            for (long tid : threadIds) {
                long cpuTime = threadBean.getThreadCpuTime(tid);
                if (cpuTime > 0) {
                    totalCpuTime += cpuTime;
                }
            }

            long currentTime = System.nanoTime();
            long lastCpu = lastCpuTime.get();
            long lastTime = lastSampleTime.get();

            int cpuUsage = 0;
            if (lastCpu > 0 && lastTime > 0) {
                long cpuDelta = totalCpuTime - lastCpu;
                long timeDelta = currentTime - lastTime;

                if (timeDelta > 0) {
                    // CPU 时间 / 经过时间 / 核心数 = 使用率
                    int processors = Runtime.getRuntime().availableProcessors();
                    cpuUsage = (int) Math.min(100, Math.round((cpuDelta * 100.0) / (timeDelta * processors)));
                }
            }

            lastCpuTime.set(totalCpuTime);
            lastSampleTime.set(currentTime);

            return cpuUsage;
        } catch (Exception e) {
            return 0;
        }
    }

    // 内部类：请求体
    @Data
    public static class LingStatusRequest {
        private RuntimeStatus status;
        private String version;
    }

    @Data
    public static class LingReloadRequest {
        private String version;
    }
}
