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
            log.error("Failed to get service metadata: {}", lingId, e);
            return ApiResponse.error("Failed to get service metadata", e);
        }
    }

    /**
     * 调用灵元服务方法（默认真实执行；simulation=true 时仅跑治理链）。
     */
    @PostMapping("/lings/{lingId}/invoke")
    public ApiResponse<InvokeResultDTO> invokeService(
            @PathVariable String lingId,
            @RequestBody InvokeRequest request) {
        try {
            boolean simulation = Boolean.TRUE.equals(request.getSimulation());
            InvokeResultDTO result = playgroundService.invokeService(
                    lingId, request.getFqsid(), request.getMethodName(),
                    request.getParameterTypes(), request.getArgs(), request.getVersion(),
                    request.getRoutingMode(), simulation);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("Service invocation failed: {}/{}", lingId, request.getFqsid(), e);
            return ApiResponse.error("Service invocation failed", e);
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
        /**
         * 目标版本号。
         * 为空时走默认实例（稳定版）；指定时走对应版本实例，用于金丝雀接口验证。
         */
        private String version;
        /**
         * 路由模式：SPECIFIED（默认，按 version 指定）/ PROPORTIONAL（按流量比例随机路由）。
         */
        private String routingMode;
        /**
         * 是否模拟调用。
         * <ul>
         *   <li>false / null（默认）：NORMAL 真实执行，可产生业务副作用——日常验接口路径</li>
         *   <li>true：SIMULATION，只跑治理链，不执行真实业务</li>
         * </ul>
         */
        private Boolean simulation;
    }
}
