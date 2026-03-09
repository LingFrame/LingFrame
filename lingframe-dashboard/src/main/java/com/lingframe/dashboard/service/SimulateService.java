package com.lingframe.dashboard.service;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.dashboard.dto.SimulateResultDTO;
import com.lingframe.dashboard.dto.StressResultDTO;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.api.exception.ServiceUnavailableException;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.monitor.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class SimulateService {

    private final LingRepository lingRepository;
    private final EventBus eventBus;
    private final CanaryRouter canaryRouter;
    private final PermissionService permissionService;
    private final InvocationPipelineEngine pipelineEngine;

    /**
     * 模拟资源访问权限校验
     * 这将发起一次完全真实的 Dry-Run 流向内核，收集决策追踪
     */
    public SimulateResultDTO simulateResource(String lingId, String resourceType) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(lingId, "Ling not active");
        }

        String traceId = TraceContext.start();

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setTraceId(traceId);
        ctx.setTargetLingId(lingId);
        ctx.setCallerLingId(lingId); // 模拟自身调用
        ctx.setResourceType(mapResourceType(resourceType));
        ctx.setResourceId("simulate:" + resourceType);
        ctx.setOperation("simulate_" + resourceType);
        ctx.setAccessType(mapAccessType(resourceType));
        ctx.setRequiredPermission(mapPermission(resourceType));
        ctx.setShouldAudit(true);
        ctx.setAuditAction("SIMULATE:" + resourceType.toUpperCase());
        // 【核心下沉】：直接要求微内核提供无副作用演练
        ctx.setDryRun(true);
        ctx.setRuntime(runtime);

        boolean allowed;
        String message;
        boolean devBypass = false;

        try {
            Object result = pipelineEngine.invoke(ctx);
            allowed = true;
            message = String.valueOf(result);

            if (isDevModeBypass(lingId, mapPermission(resourceType), mapAccessType(resourceType))) {
                devBypass = true;
                message += " (⚠️ Dev Mode Bypass)";
                ctx.addTrace(EngineTrace.builder()
                        .source("SimulateService")
                        .action("Dev mode bypass permissions check")
                        .type("WARN")
                        .depth(1)
                        .build());
            }

        } catch (LingInvocationException e) {
            allowed = false;
            message = "Pipeline Rejected: " + e.getMessage();
            ctx.addTrace(EngineTrace.builder().source("Pipeline").action("Pipeline rejected invocation: " + e.getKind())
                    .type("FAIL")
                    .depth(1).build());
        } catch (SecurityException e) {
            allowed = false;
            message = "Access Denied: " + e.getMessage();
        } catch (Exception e) {
            allowed = false;
            message = "Execution Failed: " + e.getMessage();
        }

        return SimulateResultDTO.builder()
                .traceId(traceId)
                .lingId(lingId)
                .resourceType(resourceType)
                .allowed(allowed)
                .message(message)
                .ruleSource(ctx.getRuleSource())
                .devModeBypass(devBypass)
                .timestamp(System.currentTimeMillis())
                // 暴露 Core 在干跑期间攒下的宝贵探针数据
                .traces(ctx.getTraces() != null ? new ArrayList<>(ctx.getTraces()) : null)
                .build();
    }

    /**
     * 模拟灵元间通讯 (IPC) 权限校验
     *
     * @param lingId       调用方灵元ID
     * @param targetLingId 目标灵元ID
     * @param ipcEnabled   模拟控制：是否启用 IPC 授权
     * @return 模拟结果
     */
    public SimulateResultDTO simulateIpc(String lingId, String targetLingId, boolean ipcEnabled) {
        LingRuntime sourceRuntime = lingRepository.getRuntime(lingId);
        if (sourceRuntime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!sourceRuntime.isAvailable()) {
            throw new ServiceUnavailableException(lingId, "Source ling not active");
        }

        LingRuntime targetRuntime = lingRepository.getRuntime(targetLingId);
        String traceId = TraceContext.start();

        publishTrace(traceId, lingId, "→ [IPC] Call initiated: " + targetLingId, "IN", 1);

        boolean allowed = false;
        String message;
        InvocationContext ctx = null;

        if (targetRuntime == null) {
            message = "Target ling not found";
            publishTrace(traceId, lingId, "  ✗ " + message, "ERROR", 2);
        } else if (!targetRuntime.isAvailable()) {
            message = "Target ling not active";
            publishTrace(traceId, lingId, "  ✗ " + message, "ERROR", 2);
        } else if (!ipcEnabled) {
            message = "IPC authorization disabled";
            publishTrace(traceId, lingId, "  ↳ Kernel authorization check...", "IN", 2);
            publishTrace(traceId, lingId, "    ✗ IPC access policy denied", "FAIL", 3);
        } else {
            ctx = InvocationContext.obtain();
            ctx.setTraceId(traceId);
            ctx.setTargetLingId(targetLingId);
            ctx.setCallerLingId(lingId);
            ctx.setResourceType("IPC");
            ctx.setResourceId("ipc:" + lingId + "->" + targetLingId);
            ctx.setOperation("ipc_call");
            ctx.setAccessType(AccessType.EXECUTE);
            ctx.setRequiredPermission("ipc:" + targetLingId);
            ctx.setShouldAudit(true);
            ctx.setAuditAction("IPC_CALL");

            try {
                // Pipeline Phase 1: Routing
                publishTrace(traceId, lingId, "  ↳ Pipeline routing...", "IN", 2);

                LingInstance routed = targetRuntime.getInstancePool().getDefault();
                if (routed == null) {
                    throw new ServiceUnavailableException(targetLingId, "No active instances");
                }
                targetRuntime.recordRequest(false);

                ctx.setDryRun(true);
                ctx.setRuntime(targetRuntime);

                // 🔥 通过真实 Pipeline 统一入口执行干跑
                pipelineEngine.invoke(ctx);

                allowed = true;
                message = "IPC Call Simulated Success";

                // Detect if bypassed by dev mode
                if (isDevModeBypass(lingId, "ipc:" + targetLingId, AccessType.EXECUTE)) {
                    message += " (⚠️ Dev Mode Bypass)";
                    ctx.addTrace(EngineTrace.builder()
                            .source("SimulateService")
                            .action("Dev mode IPC bypass")
                            .type("WARN")
                            .depth(1)
                            .build());
                }

            } catch (SecurityException e) {
                allowed = false;
                message = "IPC Intercepted: " + e.getMessage();
            } catch (Exception e) {
                allowed = false;
                message = "IPC Execution Failed: " + e.getMessage();
            }
        }

        return SimulateResultDTO.builder()
                .traceId(traceId)
                .lingId(lingId)
                .targetLingId(targetLingId)
                .resourceType("IPC")
                .allowed(allowed)
                .message(message)
                .traces(ctx != null && ctx.getTraces() != null ? new java.util.ArrayList<>(ctx.getTraces()) : null)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 压测单次路由
     * 由前端 setInterval 控制频率，后端每次只执行一次路由
     */
    public StressResultDTO stressTest(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(lingId, "Ling not active");
        }

        List<LingInstance> instances = runtime.getInstancePool().getActiveInstances();
        if (instances.isEmpty()) {
            throw new ServiceUnavailableException(lingId, "No active instances");
        }

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setTargetLingId(lingId);
        LingInstance instance = canaryRouter.route(instances, ctx);
        if (instance == null) {
            instance = runtime.getInstancePool().getDefault();
        }

        LingInstance defaultInstance = runtime.getInstancePool().getDefault();
        boolean isCanary = (instance != defaultInstance);

        runtime.recordRequest(isCanary);

        String version = instance.getDefinition().getVersion();
        String tag = isCanary ? "CANARY" : "STABLE";

        publishTrace(TraceContext.start(), lingId,
                String.format("→ Routed to: %s (%s)", version, tag), tag, 1);

        return StressResultDTO.builder()
                .lingId(lingId)
                .totalRequests(1)
                .v1Requests(isCanary ? 0 : 1)
                .v2Requests(isCanary ? 1 : 0)
                .activeRequests((int) runtime.getActiveRequests().get())
                .v1Percent(isCanary ? 0 : 100)
                .v2Percent(isCanary ? 100 : 0)
                .build();
    }

    // ==================== 辅助方法 ====================

    private void publishTrace(String traceId, String lingId, String action, String type, int depth) {
        try {
            eventBus.publish(new MonitoringEvents.TraceLogEvent(traceId, lingId, action, type, depth));
        } catch (Exception e) {
            log.warn("Failed to publish trace: {}", e.getMessage());
        }
    }

    /**
     * 模拟特定方法的调用
     * 通过 DryRun 交给核心引擎，消除老旧的反射加载动作
     */
    public SimulateResultDTO simulateMethod(String lingId, String className, String methodName,
            AccessType targetAccess) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(lingId, "Ling not active");
        }

        String traceId = TraceContext.start();

        boolean allowed;
        String message;
        InvocationContext ctx = null;
        boolean devBypass = false;

        try {
            ctx = InvocationContext.obtain();
            ctx.setTraceId(traceId);
            ctx.setTargetLingId(lingId);
            ctx.setCallerLingId(lingId); // 模拟自身调用
            ctx.setResourceType("METHOD");
            ctx.setResourceId(className + "#" + methodName);
            ctx.setServiceFQSID(lingId + ":" + className);
            ctx.setMethodName(methodName);
            ctx.setOperation(methodName);
            ctx.setAccessType(targetAccess);
            ctx.setShouldAudit(true);
            ctx.setAuditAction("SIMULATE:METHOD");

            ctx.setDryRun(true);
            ctx.setRuntime(runtime);

            // 🔥 通过真实 Pipeline 统一入口执行干跑推演
            Object result = pipelineEngine.invoke(ctx);

            allowed = true;
            message = "Method " + methodName + " simulated successfully: " + result;

            // 检查开发模式
            String capability = ctx.getRequiredPermission();
            if (capability != null && !capability.trim().isEmpty()) {
                if (isDevModeBypass(lingId, capability, ctx.getAccessType())) {
                    devBypass = true;
                    message += " (⚠️ Dev Mode Bypass)";
                }
            }

        } catch (LingInvocationException e) {
            allowed = false;
            message = "Pipeline Rejected: " + e.getMessage();
        } catch (Exception e) {
            allowed = false;
            message = "Simulation Exception: " + e.getMessage();
        }

        return SimulateResultDTO.builder()
                .traceId(traceId)
                .lingId(lingId)
                .resourceType("METHOD")
                .allowed(allowed)
                .message(message)
                .ruleSource(ctx != null ? ctx.getRuleSource() : null)
                .devModeBypass(devBypass)
                .timestamp(System.currentTimeMillis())
                .traces(ctx != null && ctx.getTraces() != null ? new java.util.ArrayList<>(ctx.getTraces()) : null)
                .build();
    }

    private String mapResourceType(String type) {
        if (type == null) {
            return "RESOURCE";
        }

        switch (type) {
            case "dbRead":
            case "dbWrite":
                return "DATABASE";
            case "cacheRead":
            case "cacheWrite":
                return "CACHE";
            default:
                return "RESOURCE";
        }
    }

    private AccessType mapAccessType(String type) {
        if (type == null) {
            return AccessType.EXECUTE;
        }

        switch (type) {
            case "dbRead":
            case "cacheRead":
                return AccessType.READ;
            case "dbWrite":
            case "cacheWrite":
                return AccessType.WRITE;
            default:
                return AccessType.EXECUTE;
        }
    }

    private String mapPermission(String type) {
        if (type == null) {
            return "resource:unknown";
        }

        switch (type) {
            case "dbRead":
            case "dbWrite":
                return Capabilities.STORAGE_SQL;
            case "cacheRead":
            case "cacheWrite":
                return Capabilities.CACHE_LOCAL;
            default:
                return "resource:unknown";
        }
    }

    private boolean isDevModeBypass(String lingId, String capability, AccessType accessType) {
        // 如果我们不在开发模式，就不存在豁免
        if (!LingFrameConfig.current().isDevMode()) {
            return false;
        }
        // 检查实际权限配置
        PermissionInfo info = permissionService.getPermission(lingId, capability);
        if (info == null) {
            return true; // 没有授权，却执行成功了 -> 豁免
        }
        return !info.satisfies(accessType); // 有授权但不够 -> 豁免
    }
}