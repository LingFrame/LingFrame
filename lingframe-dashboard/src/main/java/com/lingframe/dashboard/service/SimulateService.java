package com.lingframe.dashboard.service;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.plugin.PluginRuntime;
import com.lingframe.dashboard.dto.SimulateResultDTO;
import com.lingframe.api.exception.PluginNotFoundException;
import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.core.exception.InvocationException;
import com.lingframe.dashboard.dto.StressResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RequiredArgsConstructor
public class SimulateService {

    private final PluginManager pluginManager;
    private final GovernanceKernel governanceKernel;
    private final EventBus eventBus;
    private final PermissionService permissionService;

    public SimulateResultDTO simulateResource(String pluginId, String resourceType) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new PluginNotFoundException(pluginId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(pluginId, "插件未激活");
        }

        String traceId = generateTraceId();

        publishTrace(traceId, pluginId, "→ 模拟请求: " + resourceType, "IN", 1);

        InvocationContext ctx = InvocationContext.builder()
                .traceId(traceId)
                .pluginId(pluginId)
                .callerPluginId(pluginId) // 模拟该插件作为调用方
                .resourceType(mapResourceType(resourceType))
                .resourceId("simulate:" + resourceType)
                .operation("simulate_" + resourceType)
                .accessType(mapAccessType(resourceType))
                .requiredPermission(mapPermission(resourceType))
                .shouldAudit(true)
                .auditAction("SIMULATE:" + resourceType.toUpperCase())
                .build();

        boolean allowed;
        String message;

        try {
            publishTrace(traceId, pluginId, "  ↳ 内核权限校验...", "IN", 2);

            governanceKernel.invoke(runtime, getSimulateMethod(), ctx, () -> {
                return "Simulated " + resourceType + " success";
            });

            allowed = true;
            message = resourceType + " 访问成功";

            // 检测是否因开发模式豁免而通过
            if (isDevModeBypass(pluginId, mapPermission(resourceType), mapAccessType(resourceType))) {
                message += " (⚠️开发模式豁免)";
                publishTrace(traceId, pluginId, "    ! 权限不足，仅因开发模式放行", "WARN", 3);
            } else {
                publishTrace(traceId, pluginId, "    ✓ 权限验证通过", "OK", 3);
            }

        } catch (SecurityException e) {
            allowed = false;
            message = "访问被拒绝: " + e.getMessage();
            publishTrace(traceId, pluginId, "    ✗ " + message, "FAIL", 3);
        } catch (Exception e) {
            allowed = false;
            message = "执行失败: " + e.getMessage();
            publishTrace(traceId, pluginId, "    ✗ " + message, "ERROR", 3);
        }

        return SimulateResultDTO.builder()
                .traceId(traceId)
                .pluginId(pluginId)
                .resourceType(resourceType)
                .allowed(allowed)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public SimulateResultDTO simulateIpc(String pluginId, String targetPluginId, boolean ipcEnabled) {
        PluginRuntime sourceRuntime = pluginManager.getRuntime(pluginId);
        if (sourceRuntime == null) {
            throw new PluginNotFoundException(pluginId);
        }

        if (!sourceRuntime.isAvailable()) {
            throw new ServiceUnavailableException(pluginId, "源插件未激活");
        }

        PluginRuntime targetRuntime = pluginManager.getRuntime(targetPluginId);
        String traceId = generateTraceId();

        publishTrace(traceId, pluginId, "→ [IPC] 发起调用: " + targetPluginId, "IN", 1);

        boolean allowed = false;
        String message;

        if (targetRuntime == null) {
            message = "目标插件不存在";
            publishTrace(traceId, pluginId, "  ✗ " + message, "ERROR", 2);
        } else if (!targetRuntime.isAvailable()) {
            message = "目标插件未激活";
            publishTrace(traceId, pluginId, "  ✗ " + message, "ERROR", 2);
        } else if (!ipcEnabled) {
            message = "IPC 授权已关闭";
            publishTrace(traceId, pluginId, "  ↳ 内核鉴权中...", "IN", 2);
            publishTrace(traceId, pluginId, "    ✗ IPC 访问策略未放行", "FAIL", 3);
        } else {
            InvocationContext ctx = InvocationContext.builder()
                    .traceId(traceId)
                    .pluginId(targetPluginId)
                    .callerPluginId(pluginId)
                    .resourceType("IPC")
                    .resourceId("ipc:" + pluginId + "->" + targetPluginId)
                    .operation("ipc_call")
                    .accessType(AccessType.EXECUTE)
                    .requiredPermission("ipc:" + targetPluginId)
                    .shouldAudit(true)
                    .auditAction("IPC_CALL")
                    .build();

            try {
                publishTrace(traceId, pluginId, "  ↳ 内核鉴权中...", "IN", 2);

                // 🔥 模拟真实调用的路由和统计
                PluginInstance routed = targetRuntime.routeToAvailableInstance("simulate-ipc");
                targetRuntime.recordRequest(routed);

                governanceKernel.invoke(targetRuntime, getSimulateMethod(), ctx, () -> "OK");

                allowed = true;
                message = "IPC 调用成功 (" + routed.getDefinition().getVersion() + ")";

                // 检测是否因开发模式豁免而通过
                if (isDevModeBypass(pluginId, "ipc:" + targetPluginId, AccessType.EXECUTE)) {
                    message += " (⚠️开发模式豁免)";
                    publishTrace(traceId, pluginId, "    ! 权限不足，仅因开发模式放行", "WARN", 3);
                } else {
                    publishTrace(traceId, pluginId, "    ✓ 鉴权通过, 透传 Context", "OK", 3);
                }

                publishTrace(traceId, targetPluginId, "← [IPC] 收到来自 " + pluginId + " 的请求", "IN", 1);
                publishTrace(traceId, targetPluginId, "  ↳ 处理请求...", "OUT", 2);

            } catch (SecurityException e) {
                allowed = false;
                message = "IPC 被拦截: " + e.getMessage();
                publishTrace(traceId, pluginId, "    ✗ " + message, "FAIL", 3);
            } catch (Exception e) {
                allowed = false;
                message = "IPC 执行失败: " + e.getMessage();
                publishTrace(traceId, pluginId, "    ✗ " + message, "ERROR", 3);
            }
        }

        return SimulateResultDTO.builder()
                .traceId(traceId)
                .pluginId(pluginId)
                .targetPluginId(targetPluginId)
                .resourceType("IPC")
                .allowed(allowed)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 压测单次路由
     * 由前端 setInterval 控制频率，后端每次只执行一次路由
     */
    public StressResultDTO stressTest(String pluginId) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new PluginNotFoundException(pluginId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(pluginId, "插件未激活");
        }

        // 单次路由
        PluginInstance instance = runtime.routeToAvailableInstance("stress-test");
        runtime.recordRequest(instance);

        PluginInstance defaultInstance = runtime.getInstancePool().getDefault();
        boolean isCanary = (instance != defaultInstance);

        String version = instance.getDefinition().getVersion();
        String tag = isCanary ? "CANARY" : "STABLE";

        // 发布 Trace
        publishTrace(generateTraceId(), pluginId,
                String.format("→ 路由到: %s (%s)", version, tag), tag, 1);

        return StressResultDTO.builder()
                .pluginId(pluginId)
                .totalRequests(1)
                .v1Requests(isCanary ? 0 : 1)
                .v2Requests(isCanary ? 1 : 0)
                .v1Percent(isCanary ? 0 : 100)
                .v2Percent(isCanary ? 100 : 0)
                .build();
    }

    // ==================== 辅助方法 ====================

    private String generateTraceId() {
        return Long.toHexString(System.currentTimeMillis()).toUpperCase()
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0xFFFF)).toUpperCase();
    }

    private void publishTrace(String traceId, String pluginId, String action, String type, int depth) {
        try {
            eventBus.publish(new MonitoringEvents.TraceLogEvent(traceId, pluginId, action, type, depth));
        } catch (Exception e) {
            log.warn("Failed to publish trace: {}", e.getMessage());
        }
    }

    private Method getSimulateMethod() {
        try {
            return SimulateService.class.getDeclaredMethod("simulatePlaceholder");
        } catch (NoSuchMethodException e) {
            throw new InvocationException("Failed to get simulate method", e);
        }
    }

    @SuppressWarnings("unused")
    private void simulatePlaceholder() {
    }

    private String mapResourceType(String type) {
        return switch (type) {
            case "dbRead", "dbWrite" -> "DATABASE";
            case "cacheRead", "cacheWrite" -> "CACHE";
            default -> "RESOURCE";
        };
    }

    private AccessType mapAccessType(String type) {
        return switch (type) {
            case "dbRead", "cacheRead" -> AccessType.READ;
            case "dbWrite", "cacheWrite" -> AccessType.WRITE;
            default -> AccessType.EXECUTE;
        };
    }

    private String mapPermission(String type) {
        return switch (type) {
            case "dbRead", "dbWrite" -> Capabilities.STORAGE_SQL;
            case "cacheRead", "cacheWrite" -> Capabilities.CACHE_LOCAL;
            default -> "resource:unknown";
        };
    }

    private boolean isDevModeBypass(String pluginId, String capability, AccessType accessType) {
        // 如果我们不在开发模式，就不存在豁免
        if (!LingFrameConfig.current().isDevMode()) {
            return false;
        }
        // 检查实际权限配置
        var info = permissionService.getPermission(pluginId, capability);
        if (info == null) {
            return true; // 没有授权，却执行成功了 -> 豁免
        }
        return !info.satisfies(accessType); // 有授权但不够 -> 豁免
    }
}