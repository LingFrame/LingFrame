package com.lingframe.dashboard.controller;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.core.runtime.SwitchableRuntimeMode;
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
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SimulateController {

    private final SimulateService simulateService;
    private final SwitchableRuntimeMode runtimeMode;

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

    @PostMapping("/lings/{lingId}/stress")
    public ApiResponse<StressResultDTO> stressTest(
            @PathVariable String lingId) {
        try {
            StressResultDTO result = simulateService.stressTest(lingId);
            return ApiResponse.ok(result);
        } catch (LingNotFoundException | LingInvocationException e) {
            log.info("Stress test stopped: {}", e.getMessage());
            return ApiResponse.error("灵元已缺失或不可用: " + e.getMessage());
        } catch (Exception e) {
            log.error("Stress test failed", e);
            return ApiResponse.error("压测失败: " + e.getMessage());
        }
    }

    /**
     * 运行时模式切换（dev/prod），需密码二次认证。
     * <p>
     * 密码通过 lingframe.mode-switch-password 配置，未配置时切换关闭（fail-closed）。
     */
    @PostMapping("/config/mode")
    public ApiResponse<Boolean> updateMode(@RequestBody ModeRequest request) {
        try {
            boolean isDev = "dev".equalsIgnoreCase(request.getTestEnv());
            runtimeMode.switchMode(isDev, request.getPassword());
            log.info("Security Mode switched to: {} (authenticated)", isDev ? "DEV" : "PROD");
            return ApiResponse.ok(isDev);
        } catch (SecurityException e) {
            log.warn("Mode switch denied: {}", e.getMessage());
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to switch mode", e);
            return ApiResponse.error("切换模式失败: " + e.getMessage());
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

    @Data
    public static class ModeRequest {
        private String testEnv; // dev, prod
        private String password; // 模式切换密码
    }
}