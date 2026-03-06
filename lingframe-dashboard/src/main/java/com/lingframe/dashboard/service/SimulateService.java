package com.lingframe.dashboard.service;

import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.kernel.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.dashboard.dto.SimulateResultDTO;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.dashboard.dto.StressResultDTO;
import com.lingframe.dashboard.router.CanaryRouter;
import com.lingframe.core.spi.LingContainer;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class SimulateService {

    private final LingRepository lingRepository;
    private final EventBus eventBus;
    private final CanaryRouter canaryRouter;
    private final PermissionService permissionService;
    private final InvocationPipelineEngine pipelineEngine;

    public SimulateResultDTO simulateResource(String lingId, String resourceType) {
        // 🔥 尝试智能推导：寻找现有代码中的最佳替身
        AccessType targetAccess = mapAccessType(resourceType);
        String targetCapability = mapPermission(resourceType);
        Method candidate = findSimulationCandidate(lingId, targetAccess, targetCapability);

        if (candidate != null) {
            // 找到了替身，执行方法级模拟 (High Fidelity)
            String className = candidate.getDeclaringClass().getName();
            String methodName = candidate.getName();

            SimulateResultDTO result = simulateMethod(lingId, className, methodName, targetAccess);

            // Append hint to let user perceive intelligence
            return result.toBuilder()
                    .message(result.getMessage() + " [Smart Locate: " + candidate.getDeclaringClass().getSimpleName()
                            + "."
                            + methodName + "]")
                    .build();
        }

        // 没找到替身，回退到通用模拟 (Low Fidelity)
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(lingId, "Ling not active");
        }

        String traceId = generateTraceId();

        publishTrace(traceId, lingId, "→ Simulate Request: " + resourceType, "IN", 1);
        publishTrace(traceId, lingId, "  ! Business method not found, performing generic baseline check", "WARN", 1);

        String ruleSource = "GovernancePolicy:" + mapPermission(resourceType);

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setTraceId(traceId);
        ctx.setTargetLingId(lingId); // Set via setTargetLingId since setLingId is deprecated
        ctx.setCallerLingId(lingId); // 模拟该灵元作为调用方
        ctx.setResourceType(mapResourceType(resourceType));
        ctx.setResourceId("simulate:" + resourceType);
        ctx.setOperation("simulate_" + resourceType);
        ctx.setAccessType(mapAccessType(resourceType));
        ctx.setRequiredPermission(mapPermission(resourceType));
        ctx.setShouldAudit(true);
        ctx.setAuditAction("SIMULATE:" + resourceType.toUpperCase());
        ctx.setRuleSource(ruleSource);

        // 预设路由目标实例和模拟 callable
        LingInstance instance = runtime.getInstancePool().getDefault();
        ctx.getAttachments().put("ling.target.instance", instance);
        ctx.setServiceFQSID(lingId + ":simulate_" + resourceType);
        ctx.getAttachments().put("ling.simulate.callable",
                (Callable<Object>) () -> resourceType + " Access Success");

        boolean allowed;
        String message;
        boolean devBypass = false;

        try {
            publishTrace(traceId, lingId, "  ↳ Pipeline execution...", "IN", 2);

            // 🔥 通过真实 Pipeline 统一入口执行
            Object result = pipelineEngine.invoke(ctx);

            allowed = true;
            message = String.valueOf(result);

            // 检测是否因开发模式豁免而通过
            if (isDevModeBypass(lingId, mapPermission(resourceType), mapAccessType(resourceType))) {
                devBypass = true;
                message += " (⚠️ Dev Mode Bypass)";
                publishTrace(traceId, lingId,
                        "    ! Permission insufficient, bypassed by Dev Mode (Source: " + ctx.getRuleSource() + ")",
                        "WARN", 3);
            } else {
                publishTrace(traceId, lingId, "    ✓ Permission verified (Source: " + ctx.getRuleSource() + ")", "OK",
                        3);
            }

        } catch (LingInvocationException e) {
            allowed = false;
            message = "Pipeline Rejected: " + e.getMessage();
            publishTrace(traceId, lingId, "    ✗ " + message, "FAIL", 3);
        } catch (SecurityException e) {
            allowed = false;
            message = "Access Denied: " + e.getMessage();
            publishTrace(traceId, lingId, "    ✗ " + message, "FAIL", 3);
        } catch (Exception e) {
            allowed = false;
            message = "Execution Failed: " + e.getMessage();
            publishTrace(traceId, lingId, "    ✗ " + message, "ERROR", 3);
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
                .build();
    }

    public SimulateResultDTO simulateIpc(String lingId, String targetLingId, boolean ipcEnabled) {
        LingRuntime sourceRuntime = lingRepository.getRuntime(lingId);
        if (sourceRuntime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!sourceRuntime.isAvailable()) {
            throw new ServiceUnavailableException(lingId, "Source ling not active");
        }

        LingRuntime targetRuntime = lingRepository.getRuntime(targetLingId);
        String traceId = generateTraceId();

        publishTrace(traceId, lingId, "→ [IPC] Call initiated: " + targetLingId, "IN", 1);

        boolean allowed = false;
        String message;

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
            InvocationContext ctx = InvocationContext.obtain();
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

                // 预设路由目标和模拟 callable
                ctx.getAttachments().put("ling.target.instance", routed);
                ctx.setServiceFQSID(targetLingId + ":ipc_call");
                ctx.getAttachments().put("ling.simulate.callable",
                        (Callable<Object>) () -> "IPC Call Success");

                // 🔥 通过真实 Pipeline 统一入口执行
                publishTrace(traceId, lingId, "  ↳ Pipeline execution...", "IN", 2);
                pipelineEngine.invoke(ctx);

                allowed = true;
                message = "IPC Call Success (" + routed.getDefinition().getVersion() + ")";

                // Detect if bypassed by dev mode
                if (isDevModeBypass(lingId, "ipc:" + targetLingId, AccessType.EXECUTE)) {
                    message += " (⚠️ Dev Mode Bypass)";
                    publishTrace(traceId, lingId, "    ! Permission insufficient, bypassed by Dev Mode", "WARN", 3);
                } else {
                    publishTrace(traceId, lingId, "    ✓ Authorized, Context propagated", "OK", 3);
                }

                publishTrace(traceId, targetLingId, "← [IPC] Received request from " + lingId, "IN", 1);
                publishTrace(traceId, targetLingId, "  ↳ Processing request...", "OUT", 2);

            } catch (SecurityException e) {
                allowed = false;
                message = "IPC Intercepted: " + e.getMessage();
                publishTrace(traceId, lingId, "    ✗ " + message, "FAIL", 3);
            } catch (Exception e) {
                allowed = false;
                message = "IPC Execution Failed: " + e.getMessage();
                publishTrace(traceId, lingId, "    ✗ " + message, "ERROR", 3);
            }
        }

        return SimulateResultDTO.builder()
                .traceId(traceId)
                .lingId(lingId)
                .targetLingId(targetLingId)
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
    public StressResultDTO stressTest(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(lingId, "灵元未激活");
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

        // Publish Trace
        publishTrace(generateTraceId(), lingId,
                String.format("→ Routed to: %s (%s)", version, tag), tag, 1);

        return StressResultDTO.builder()
                .lingId(lingId)
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

    private void publishTrace(String traceId, String lingId, String action, String type, int depth) {
        try {
            eventBus.publish(new MonitoringEvents.TraceLogEvent(traceId, lingId, action, type, depth));
        } catch (Exception e) {
            log.warn("Failed to publish trace: {}", e.getMessage());
        }
    }

    private Method getSimulateMethod() {
        try {
            return SimulateService.class.getDeclaredMethod("simulatePlaceholder");
        } catch (NoSuchMethodException e) {
            throw new LingInvocationException("local:simulatePlaceholder",
                    LingInvocationException.ErrorKind.INTERNAL_ERROR, e);
        }
    }

    /**
     * 模拟特定方法的调用
     * 🔥 通过反射加载真实方法元数据，从而支持注解级权限校验
     */
    public SimulateResultDTO simulateMethod(String lingId, String className, String methodName,
            AccessType targetAccess) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new ServiceUnavailableException(lingId, "灵元未激活");
        }

        String traceId = generateTraceId();
        publishTrace(traceId, lingId, "→ Simulate Method: " + methodName, "IN", 1);

        boolean allowed;
        String message;
        InvocationContext ctx = null;
        boolean devBypass = false;

        try {
            // 1. 获取灵元类加载器
            ClassLoader lingLoader = runtime.getInstancePool().getDefault()
                    .getContainer().getClassLoader();

            // 2. 加载真实类和方法
            Class<?> targetClass = lingLoader.loadClass(className);
            // 简化处理：假设是无参方法，或仅根据名称匹配（生产环境应支持参数签名）
            Method targetMethod = findMethodByName(targetClass, methodName);

            // 3. 构建上下文 - callerLingId 设为被测灵元，这样权限检查针对正确的主体
            // 🔥 从注解读取权限声明并设置 ruleSource
            RequiresPermission annotation = targetMethod.getAnnotation(RequiresPermission.class);
            String methodRuleSource;
            String requiredPerm;
            if (annotation != null) {
                requiredPerm = annotation.value();
                methodRuleSource = "@RequiresPermission(" + requiredPerm + ") on " + className + "#" + methodName;
            } else {
                requiredPerm = GovernanceStrategy.inferPermission(targetMethod);
                methodRuleSource = requiredPerm != null
                        ? "InferredFromMethod:" + className + "#" + methodName
                        : null;
            }

            ctx = InvocationContext.obtain();
            ctx.setTraceId(traceId);
            ctx.setTargetLingId(lingId);
            ctx.setCallerLingId(lingId); // 模拟灵元自己调用自己的方法
            ctx.setResourceType("METHOD");
            ctx.setResourceId(className + "#" + methodName);
            ctx.setOperation(methodName);
            ctx.setAccessType(targetAccess); // 使用传递的目标 AccessType
            ctx.setRequiredPermission(requiredPerm);
            ctx.setShouldAudit(true);
            ctx.setAuditAction("SIMULATE:METHOD");
            ctx.setRuleSource(methodRuleSource);

            // 预设路由目标实例和模拟 callable
            LingInstance instance = runtime.getInstancePool().getDefault();
            ctx.getAttachments().put("ling.target.instance", instance);
            ctx.setServiceFQSID(lingId + ":" + className);
            ctx.getAttachments().put("ling.simulate.callable",
                    (Callable<Object>) () -> "Simulated " + methodName + " success");

            // 🔥 通过真实 Pipeline 统一入口执行
            publishTrace(traceId, lingId, "  ↳ Pipeline execution...", "IN", 2);
            Object result = pipelineEngine.invoke(ctx);

            allowed = true;
            message = "Method " + methodName + " allowed";

            // 🔥 统一检测开发模式豁免逻辑
            String capability = ctx.getRequiredPermission();
            AccessType inferredAccess = ctx.getAccessType();

            // 如果找到了需要检查的 capability，则进行豁免检测
            if (capability != null && !capability.trim().isEmpty()) {
                if (isDevModeBypass(lingId, capability, inferredAccess)) {
                    devBypass = true;
                    message += " (⚠️ Dev Mode Bypass)";
                    publishTrace(traceId, lingId,
                            "    ! Permission insufficient, bypassed by Dev Mode (Source: "
                                    + ctx.getRuleSource() + ")",
                            "WARN", 3);
                } else {
                    publishTrace(traceId, lingId, "    ✓ Permission verified (Source: " + ctx.getRuleSource() + ")",
                            "OK", 3);
                }
            } else {
                // No permission declared
                publishTrace(traceId, lingId, "    ✓ Permission verified (No explicit permission declared)", "OK", 3);
            }

        } catch (ClassNotFoundException e) {
            allowed = false;
            message = "Class not found: " + className;
            publishTrace(traceId, lingId, "    ✗ " + message, "ERROR", 3);
        } catch (NoSuchMethodException e) {
            allowed = false;
            message = "Method not found: " + methodName;
            publishTrace(traceId, lingId, "    ✗ " + message, "ERROR", 3);
        } catch (LingInvocationException e) {
            allowed = false;
            message = "Pipeline Rejected: " + e.getMessage();
            publishTrace(traceId, lingId, "    ✗ " + message, "FAIL", 3);
        } catch (SecurityException e) {
            allowed = false;
            message = "Access Denied: " + e.getMessage();
            publishTrace(traceId, lingId, "    ✗ " + message, "FAIL", 3);
        } catch (Exception e) {
            allowed = false;
            message = "Simulation Exception: " + e.getMessage();
            publishTrace(traceId, lingId, "    ✗ " + message, "ERROR", 3);
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

    private Method findSimulationCandidate(String lingId, AccessType targetAccess, String targetCapability) {
        try {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null || !runtime.isAvailable()) {
                return null;
            }

            LingContainer container = runtime.getInstancePool().getDefault().getContainer();
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
                        if (m.isAnnotationPresent(RequiresPermission.class)) {
                            String capability = m.getAnnotation(RequiresPermission.class).value();
                            return capability.equals(targetCapability);
                        }
                        return false;
                    })
                    .collect(Collectors.toList());

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
        if (m.isAnnotationPresent(RequiresPermission.class)) {
            String capability = m.getAnnotation(RequiresPermission.class).value();
            if (capability.equals(targetCapability)) {
                score += 200; // 完全匹配，优先级最高
            }
        }

        // 维度 1: 显式权限定义
        if (m.isAnnotationPresent(RequiresPermission.class)) {
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