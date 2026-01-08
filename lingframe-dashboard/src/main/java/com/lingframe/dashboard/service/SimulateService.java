package com.lingframe.dashboard.service;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.plugin.PluginRuntime;
import com.lingframe.dashboard.dto.SimulateResultDTO;
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

    public SimulateResultDTO simulateResource(String pluginId, String resourceType) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new IllegalArgumentException("插件不存在: " + pluginId);
        }

        if (!runtime.isAvailable()) {
            throw new IllegalStateException("插件未激活: " + pluginId);
        }

        String traceId = generateTraceId();

        publishTrace(traceId, pluginId, "→ 模拟请求: " + resourceType, "IN", 1);

        InvocationContext ctx = InvocationContext.builder()
                .traceId(traceId)
                .pluginId(pluginId)
                .callerPluginId("dashboard-simulate")
                .resourceType(mapResourceType(resourceType))
                .resourceId("simulate:" + resourceType)
                .operation("simulate_" + resourceType)
                .accessType(mapAccessType(resourceType))
                .requiredPermission(mapPermission(resourceType))
                .shouldAudit(true)
                .auditAction("SIMULATE:" + resourceType.toUpperCase())
                .build();

        boolean allowed = false;
        String message;

        try {
            publishTrace(traceId, pluginId, "  ↳ 内核权限校验...", "IN", 2);

            governanceKernel.invoke(runtime, getSimulateMethod(), ctx, () -> {
                return "Simulated " + resourceType + " success";
            });

            allowed = true;
            message = resourceType + " 访问成功";
            publishTrace(traceId, pluginId, "    ✓ 权限验证通过", "OK", 3);

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
            throw new IllegalArgumentException("源插件不存在: " + pluginId);
        }

        if (!sourceRuntime.isAvailable()) {
            throw new IllegalStateException("源插件未激活: " + pluginId);
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

                governanceKernel.invoke(targetRuntime, getSimulateMethod(), ctx, () -> "OK");

                allowed = true;
                message = "IPC 调用成功";
                publishTrace(traceId, pluginId, "    ✓ 鉴权通过, 透传 Context", "OK", 3);

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

    public StressResultDTO stressTest(String pluginId, int count) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new IllegalArgumentException("插件不存在: " + pluginId);
        }

        if (!runtime.isAvailable()) {
            throw new IllegalStateException("插件未激活: " + pluginId);
        }

        count = Math.min(count, 100);
        int v1Hit = 0, v2Hit = 0;

        String batchTraceId = generateTraceId();
        publishTrace(batchTraceId, pluginId, String.format("🚀 压测开始: 共 %d 次请求", count), "START", 0);

        for (int i = 0; i < count; i++) {
            try {
                PluginInstance instance = runtime.routeToAvailableInstance("stress-test");
                runtime.recordRequest(instance);

                PluginInstance defaultInstance = runtime.getInstancePool().getDefault();
                boolean isCanary = (instance != defaultInstance);

                if (isCanary) {
                    v2Hit++;
                } else {
                    v1Hit++;
                }

                if (i < 3 || i % 10 == 0 || i == count - 1) {
                    String version = instance.getDefinition().getVersion();
                    String tag = isCanary ? "CANARY" : "STABLE";
                    publishTrace(generateTraceId(), pluginId,
                            String.format("→ #%d 路由到: %s (%s)", i + 1, version, tag), tag, 1);
                }
            } catch (Exception e) {
                log.warn("Stress request {} failed: {}", i, e.getMessage());
            }
        }

        double v1Pct = count > 0 ? (v1Hit * 100.0 / count) : 0;
        double v2Pct = count > 0 ? (v2Hit * 100.0 / count) : 0;

        publishTrace(batchTraceId, pluginId,
                String.format("✅ 压测完成: V1=%d (%.1f%%), V2=%d (%.1f%%)", v1Hit, v1Pct, v2Hit, v2Pct),
                "COMPLETE", 0);

        return StressResultDTO.builder()
                .pluginId(pluginId)
                .totalRequests(count)
                .v1Requests(v1Hit)
                .v2Requests(v2Hit)
                .v1Percent(v1Pct)
                .v2Percent(v2Pct)
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
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    private void simulatePlaceholder() {}

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
            case "dbRead" -> "resource:db:read";
            case "dbWrite" -> "resource:db:write";
            case "cacheRead" -> "resource:cache:read";
            case "cacheWrite" -> "resource:cache:write";
            default -> "resource:unknown";
        };
    }
}