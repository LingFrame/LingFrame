package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.*;
import com.lingframe.dashboard.service.SimulateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/simulate")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(
        prefix = "lingframe.dashboard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class SimulateController {

    private final SimulateService simulateService;

    @PostMapping("/plugins/{pluginId}/resource")
    public ApiResponse<SimulateResultDTO> simulateResource(
            @PathVariable String pluginId,
            @RequestBody ResourceRequest request) {
        try {
            SimulateResultDTO result = simulateService.simulateResource(pluginId, request.getResourceType());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("Simulate resource failed", e);
            return ApiResponse.error("模拟失败: " + e.getMessage());
        }
    }

    @PostMapping("/plugins/{pluginId}/ipc")
    public ApiResponse<SimulateResultDTO> simulateIpc(
            @PathVariable String pluginId,
            @RequestBody IpcRequest request) {
        try {
            SimulateResultDTO result = simulateService.simulateIpc(
                    pluginId, request.getTargetPluginId(), request.isIpcEnabled());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("Simulate IPC failed", e);
            return ApiResponse.error("IPC 模拟失败: " + e.getMessage());
        }
    }

    @PostMapping("/plugins/{pluginId}/stress")
    public ApiResponse<StressResultDTO> stressTest(
            @PathVariable String pluginId,
            @RequestBody StressRequest request) {
        try {
            StressResultDTO result = simulateService.stressTest(pluginId, request.getCount());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("Stress test failed", e);
            return ApiResponse.error("压测失败: " + e.getMessage());
        }
    }

    // 内部类：请求体
    @lombok.Data
    public static class ResourceRequest {
        private String resourceType;// dbRead, dbWrite, cacheRead, cacheWrite
    }

    @lombok.Data
    public static class IpcRequest {
        private String targetPluginId;
        private boolean ipcEnabled;
    }

    @lombok.Data
    public static class StressRequest {
        private int count = 10;// 默认 10 次
    }
}