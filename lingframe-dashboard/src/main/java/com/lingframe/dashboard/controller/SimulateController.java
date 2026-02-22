package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.dashboard.dto.*;
import com.lingframe.dashboard.service.SimulateService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/simulate")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SimulateController {

    private final SimulateService simulateService;

    @PostMapping("/lings/{lingId}/resource")
    public ApiResponse<SimulateResultDTO> simulateResource(
            @PathVariable String lingId,
            @RequestBody ResourceRequest request) {
        try {
            SimulateResultDTO result = simulateService.simulateResource(lingId, request.getResourceType());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("Simulate resource failed", e);
            return ApiResponse.error("模拟失败: " + e.getMessage());
        }
    }

    @PostMapping("/lings/{lingId}/ipc")
    public ApiResponse<SimulateResultDTO> simulateIpc(
            @PathVariable String lingId,
            @RequestBody IpcRequest request) {
        try {
            SimulateResultDTO result = simulateService.simulateIpc(
                    lingId, request.getTargetLingId(), request.isIpcEnabled());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("Simulate IPC failed", e);
            return ApiResponse.error("IPC 模拟失败: " + e.getMessage());
        }
    }

    @PostMapping("/config/mode")
    public ApiResponse<Boolean> updateDevMode(@RequestBody ModeRequest request) {
        try {
            boolean isDev = "dev".equalsIgnoreCase(request.getTestEnv());
            LingFrameConfig.current().setDevMode(isDev);
            log.info("Security Mode switched to: {} (DevMode={})", isDev ? "DEV" : "PROD", isDev);
            return ApiResponse.ok(isDev);
        } catch (Exception e) {
            log.error("Failed to switch mode", e);
            return ApiResponse.error("切换模式失败: " + e.getMessage());
        }
    }

    // 内部类：请求体
    @Data
    public static class ModeRequest {
        private String testEnv; // dev, prod
    }

    @PostMapping("/lings/{lingId}/stress")
    public ApiResponse<StressResultDTO> stressTest(
            @PathVariable String lingId) {
        try {
            StressResultDTO result = simulateService.stressTest(lingId);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("Stress test failed", e);
            return ApiResponse.error("压测失败: " + e.getMessage());
        }
    }

    // 内部类：请求体
    @Data
    public static class ResourceRequest {
        private String resourceType;// dbRead, dbWrite, cacheRead, cacheWrite
    }

    @Data
    public static class IpcRequest {
        private String targetLingId;
        private boolean ipcEnabled;
    }
}