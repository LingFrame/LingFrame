package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.InvokeResultDTO;
import com.lingframe.dashboard.dto.ServiceMetadataDTO;
import com.lingframe.dashboard.service.ServicePlaygroundService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 服务演练场控制器
 * <p>
 * 提供灵元服务元数据查询和真实调用接口，
 * 供 Dashboard 前端展示服务列表并执行调用验证。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/playground")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ServicePlaygroundController {

    private final ServicePlaygroundService playgroundService;

    /**
     * 获取灵元的所有服务元数据
     */
    @GetMapping("/lings/{lingId}/services")
    public ApiResponse<List<ServiceMetadataDTO>> getServices(@PathVariable String lingId) {
        try {
            return ApiResponse.ok(playgroundService.getServices(lingId));
        } catch (Exception e) {
            log.error("获取服务元数据失败: {}", lingId, e);
            return ApiResponse.error("获取服务元数据失败: " + e.getMessage());
        }
    }

    /**
     * 真实调用灵元服务方法
     */
    @PostMapping("/lings/{lingId}/invoke")
    public ApiResponse<InvokeResultDTO> invokeService(
            @PathVariable String lingId,
            @RequestBody InvokeRequest request) {
        try {
            InvokeResultDTO result = playgroundService.invokeService(
                    lingId, request.getFqsid(), request.getMethodName(),
                    request.getParameterTypes(), request.getArgs());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("服务调用失败: {}/{}", lingId, request.getFqsid(), e);
            return ApiResponse.error("服务调用失败: " + e.getMessage());
        }
    }

    @Data
    public static class InvokeRequest {
        /** 服务 FQSID */
        private String fqsid;
        /** 方法名 */
        private String methodName;
        /** 参数类型列表（用于方法定位） */
        private String[] parameterTypes;
        /** 调用参数值 */
        private Object[] args;
    }
}
