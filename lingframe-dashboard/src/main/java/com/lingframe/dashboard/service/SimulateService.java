package com.lingframe.dashboard.service;

import com.lingframe.api.annotation.RequiresPermission;
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
import com.lingframe.core.spi.PluginContainer;
import com.lingframe.core.strategy.GovernanceStrategy;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RequiredArgsConstructor
public class SimulateService {

    private final PluginManager pluginManager;
    private final GovernanceKernel governanceKernel;
    private final EventBus eventBus;
    private final PermissionService permissionService;

    public SimulateResultDTO simulateResource(String pluginId, String resourceType) {
        // 🔥 尝试智能推导：寻找现有代码中的最佳替身
        AccessType targetAccess = mapAccessType(resourceType);
        String targetCapability = mapPermission(resourceType);
        Method candidate = findSimulationCandidate(pluginId, targetAccess, targetCapability);

        if (candidate != null) {
            // 找到了替身，执行方法级模拟 (High Fidelity)
            String className = candidate.getDeclaringClass().getName();
            String methodName = candidate.getName();

            SimulateResultDTO result = simulateMethod(pluginId, className, methodName, targetAccess);

            // Append hint to let user perceive intelligence
            return result.toBuilder()
                    .message(result.getMessage() + " [Smart Locate: " + candidate.getDeclaringClass().getSimpleName()
                            + "."
                            + methodName + "]")
                    .build();
        }

        // 没找到替身，回退到通用模拟 (Low Fidelity)
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new PluginNotFoundException(pluginId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(pluginId, "Plugin not active");
        }

        String traceId = generateTraceId();

        publishTrace(traceId, pluginId, "→ Simulate Request: " + resourceType, "IN", 1);
        publishTrace(traceId, pluginId, "  ! Business method not found, performing generic baseline check", "WARN", 1);

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
        boolean devBypass = false;

        try {
            publishTrace(traceId, pluginId, "  ↳ Kernel authorization check...", "IN", 2);

            governanceKernel.invoke(runtime, getSimulateMethod(), ctx, () -> {
                return "Simulated " + resourceType + " success";
            });

            allowed = true;
            message = resourceType + " Access Success";

            // 检测是否因开发模式豁免而通过
            if (isDevModeBypass(pluginId, mapPermission(resourceType), mapAccessType(resourceType))) {
                devBypass = true;
                message += " (⚠️ Dev Mode Bypass)";
                publishTrace(traceId, pluginId,
                        "    ! Permission insufficient, bypassed by Dev Mode (Source: " + ctx.getRuleSource() + ")",
                        "WARN", 3);
            } else {
                publishTrace(traceId, pluginId, "    ✓ Permission verified", "OK", 3);
            }

        } catch (SecurityException e) {
            allowed = false;
            message = "Access Denied: " + e.getMessage();
            publishTrace(traceId, pluginId, "    ✗ " + message, "FAIL", 3);
        } catch (Exception e) {
            allowed = false;
            message = "Execution Failed: " + e.getMessage();
            publishTrace(traceId, pluginId, "    ✗ " + message, "ERROR", 3);
        }

        return SimulateResultDTO.builder()
                .traceId(traceId)
                .pluginId(pluginId)
                .resourceType(resourceType)
                .allowed(allowed)
                .message(message)
                .ruleSource(ctx.getRuleSource())
                .devModeBypass(devBypass)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public SimulateResultDTO simulateIpc(String pluginId, String targetPluginId, boolean ipcEnabled) {
        PluginRuntime sourceRuntime = pluginManager.getRuntime(pluginId);
        if (sourceRuntime == null) {
            throw new PluginNotFoundException(pluginId);
        }

        if (!sourceRuntime.isAvailable()) {
            throw new ServiceUnavailableException(pluginId, "Source plugin not active");
        }

        PluginRuntime targetRuntime = pluginManager.getRuntime(targetPluginId);
        String traceId = generateTraceId();

        publishTrace(traceId, pluginId, "→ [IPC] Call initiated: " + targetPluginId, "IN", 1);

        boolean allowed = false;
        String message;

        if (targetRuntime == null) {
            message = "Target plugin not found";
            publishTrace(traceId, pluginId, "  ✗ " + message, "ERROR", 2);
        } else if (!targetRuntime.isAvailable()) {
            message = "Target plugin not active";
            publishTrace(traceId, pluginId, "  ✗ " + message, "ERROR", 2);
        } else if (!ipcEnabled) {
            message = "IPC authorization disabled";
            publishTrace(traceId, pluginId, "  ↳ Kernel authorization check...", "IN", 2);
            publishTrace(traceId, pluginId, "    ✗ IPC access policy denied", "FAIL", 3);
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
                publishTrace(traceId, pluginId, "  ↳ Kernel authorization check...", "IN", 2);

                // 🔥 模拟真实调用的路由和统计
                PluginInstance routed = targetRuntime.routeToAvailableInstance("simulate-ipc");
                targetRuntime.recordRequest(routed);

                governanceKernel.invoke(targetRuntime, getSimulateMethod(), ctx, () -> "OK");

                allowed = true;
                message = "IPC Call Success (" + routed.getDefinition().getVersion() + ")";

                // Detect if bypassed by dev mode
                if (isDevModeBypass(pluginId, "ipc:" + targetPluginId, AccessType.EXECUTE)) {
                    message += " (⚠️ Dev Mode Bypass)";
                    publishTrace(traceId, pluginId, "    ! Permission insufficient, bypassed by Dev Mode", "WARN", 3);
                } else {
                    publishTrace(traceId, pluginId, "    ✓ Authorized, Context propagated", "OK", 3);
                }

                publishTrace(traceId, targetPluginId, "← [IPC] Received request from " + pluginId, "IN", 1);
                publishTrace(traceId, targetPluginId, "  ↳ Processing request...", "OUT", 2);

            } catch (SecurityException e) {
                allowed = false;
                message = "IPC Intercepted: " + e.getMessage();
                publishTrace(traceId, pluginId, "    ✗ " + message, "FAIL", 3);
            } catch (Exception e) {
                allowed = false;
                message = "IPC Execution Failed: " + e.getMessage();
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

        // Publish Trace
        publishTrace(generateTraceId(), pluginId,
                String.format("→ Routed to: %s (%s)", version, tag), tag, 1);

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

    /**
     * 模拟特定方法的调用
     * 🔥 通过反射加载真实方法元数据，从而支持注解级权限校验
     */
    public SimulateResultDTO simulateMethod(String pluginId, String className, String methodName,
            AccessType targetAccess) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new PluginNotFoundException(pluginId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(pluginId, "插件未激活");
        }

        String traceId = generateTraceId();
        publishTrace(traceId, pluginId, "→ Simulate Method: " + methodName, "IN", 1);

        boolean allowed;
        String message;
        InvocationContext ctx = null;
        boolean devBypass = false;

        try {
            // 1. 获取插件类加载器
            ClassLoader pluginLoader = runtime.getInstancePool().getDefault()
                    .getContainer().getClassLoader();

            // 2. 加载真实类和方法
            Class<?> targetClass = pluginLoader.loadClass(className);
            // 简化处理：假设是无参方法，或仅根据名称匹配（生产环境应支持参数签名）
            Method targetMethod = findMethodByName(targetClass, methodName);

            // 3. 构建上下文 - callerPluginId 设为被测插件，这样权限检查针对正确的主体
            ctx = InvocationContext.builder()
                    .traceId(traceId)
                    .pluginId(pluginId)
                    .callerPluginId(pluginId) // 模拟插件自己调用自己的方法
                    .resourceType("METHOD")
                    .resourceId(className + "#" + methodName)
                    .operation(methodName)
                    .accessType(targetAccess) // 使用传递的目标 AccessType
                    .shouldAudit(true)
                    .auditAction("SIMULATE:METHOD")
                    .build();

            // 4. Call Kernel (execute fake logic)
            publishTrace(traceId, pluginId, "  ↳ Kernel fine-grained auth...", "IN", 2);

            governanceKernel.invoke(runtime, targetMethod, ctx, () -> {
                return "Simulated " + methodName + " success";
            });

            allowed = true;
            message = "Method " + methodName + " allowed";

            // 🔥 统一检测开发模式豁免逻辑
            // 优先从注解读取 capability，其次使用 context 中的 requiredPermission
            String capability = null;
            AccessType inferredAccess = ctx.getAccessType();

            var annotation = targetMethod.getAnnotation(RequiresPermission.class);
            if (annotation != null) {
                capability = annotation.value();
                // inferredAccess 由 context 决定，不再重新推导
            } else if (ctx.getRequiredPermission() != null && !ctx.getRequiredPermission().isBlank()) {
                capability = ctx.getRequiredPermission();
            }

            // 如果找到了需要检查的 capability，则进行豁免检测
            if (capability != null) {
                if (isDevModeBypass(pluginId, capability, inferredAccess)) {
                    devBypass = true;
                    message += " (⚠️ Dev Mode Bypass)";
                    publishTrace(traceId, pluginId,
                            "    ! Permission insufficient, bypassed by Dev Mode (Source: "
                                    + (ctx != null ? ctx.getRuleSource() : "Unknown") + ")",
                            "WARN", 3);
                } else {
                    publishTrace(traceId, pluginId, "    ✓ Permission verified (Annotation check)", "OK", 3);
                }
            } else {
                // No permission declared
                publishTrace(traceId, pluginId, "    ✓ Permission verified (No explicit permission declared)", "OK", 3);
            }

        } catch (ClassNotFoundException e) {
            allowed = false;
            message = "Class not found: " + className;
            publishTrace(traceId, pluginId, "    ✗ " + message, "ERROR", 3);
        } catch (NoSuchMethodException e) {
            allowed = false;
            message = "Method not found: " + methodName;
            publishTrace(traceId, pluginId, "    ✗ " + message, "ERROR", 3);
        } catch (SecurityException e) {
            allowed = false;
            message = "Access Denied: " + e.getMessage();
            publishTrace(traceId, pluginId, "    ✗ " + message, "FAIL", 3);
        } catch (Exception e) {
            allowed = false;
            message = "Simulation Exception: " + e.getMessage();
            publishTrace(traceId, pluginId, "    ✗ " + message, "ERROR", 3);
        }

        return SimulateResultDTO.builder()
                .traceId(traceId)
                .pluginId(pluginId)
                .resourceType("METHOD")
                .allowed(allowed)
                .message(message)
                .ruleSource(ctx != null ? ctx.getRuleSource() : null)
                .devModeBypass(devBypass)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private Method findMethodByName(Class<?> clazz, String name) throws NoSuchMethodException {
        // 简单查找逻辑，仅用于演示。生产环境需处理重载。
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private Method findSimulationCandidate(String pluginId, AccessType targetAccess, String targetCapability) {
        try {
            PluginRuntime runtime = pluginManager.getRuntime(pluginId);
            if (runtime == null || !runtime.isAvailable()) {
                return null;
            }

            PluginContainer container = runtime.getInstancePool().getDefault().getContainer();
            String[] beanNames = container.getBeanNames();

            // 候选池：找到所有符合 AccessType 的方法
            List<Method> candidates = new ArrayList<>();

            for (String beanName : beanNames) {
                Object bean = container.getBean(beanName);
                if (bean == null)
                    continue;

                Class<?> beanClass = bean.getClass();

                // 只扫描控制器和服务类 (避免无关 Bean 干扰)
                if (isBusinessBean(beanClass)) {
                    for (Method m : beanClass.getDeclaredMethods()) {
                        // 1. 类型匹配 (WRITE vs WRITE)
                        if (GovernanceStrategy.inferAccessType(m.getName()) == targetAccess) {
                            candidates.add(m);
                        }
                    }
                }
            }

            // 🔥 治理中心优先策略：只返回 capability 完全匹配的方法
            // 如果找不到匹配的，宁可不用智能候选，走通用路径
            List<Method> capabilityMatched = candidates.stream()
                    .filter(m -> {
                        if (m.isAnnotationPresent(com.lingframe.api.annotation.RequiresPermission.class)) {
                            String capability = m.getAnnotation(com.lingframe.api.annotation.RequiresPermission.class)
                                    .value();
                            return capability.equals(targetCapability);
                        }
                        return false;
                    })
                    .toList();

            if (!capabilityMatched.isEmpty()) {
                return capabilityMatched.stream()
                        .max(Comparator.comparingInt(m -> calculateScore(m, targetCapability)))
                        .orElse(null);
            }

            // 没有找到 capability 匹配的方法，返回 null，走通用模拟路径
            return null;

        } catch (Exception e) {
            log.warn("Failed to find simulation candidate: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 计算候选方法的权重分数
     * 规则：
     * 1. Capability 匹配 (+200)
     * 2. 有注解 > 无注解 (+100)
     * 3. Service 层 > Component 层 > Controller 层 (+50 / +30 / +10)
     */
    private int calculateScore(Method m, String targetCapability) {
        int score = 0;

        // 维度 0: Capability 匹配 (最最重要！)
        if (m.isAnnotationPresent(com.lingframe.api.annotation.RequiresPermission.class)) {
            String capability = m.getAnnotation(com.lingframe.api.annotation.RequiresPermission.class).value();
            if (capability.equals(targetCapability)) {
                score += 200; // 完全匹配，优先级最高
            }
        }

        // 维度 1: 显式权限定义
        if (m.isAnnotationPresent(com.lingframe.api.annotation.RequiresPermission.class)) {
            score += 100;
        }

        // 维度 2: 架构分层优先级
        Class<?> clazz = m.getDeclaringClass();
        if (clazz.isAnnotationPresent(Service.class)) {
            score += 50;
        } else if (clazz.isAnnotationPresent(Component.class)) {
            score += 30; // 为了兼容某些用 @Component 也就是 Service 的情况
        } else if (clazz.isAnnotationPresent(Controller.class) || clazz.isAnnotationPresent(RestController.class)) {
            score += 10;
        }

        return score;
    }

    private boolean isBusinessBean(Class<?> clazz) {
        return clazz.isAnnotationPresent(Service.class) ||
                clazz.isAnnotationPresent(Component.class) ||
                clazz.isAnnotationPresent(Controller.class) ||
                clazz.isAnnotationPresent(RestController.class);
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