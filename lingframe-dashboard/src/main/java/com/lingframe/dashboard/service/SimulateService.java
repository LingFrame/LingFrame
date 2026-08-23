package com.lingframe.dashboard.service;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationContextBuilder;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.routing.ProviderDescriptor;
import com.lingframe.dashboard.dto.ContractStressStepDTO;
import com.lingframe.dashboard.dto.SimulateResultDTO;
import com.lingframe.dashboard.dto.StressResultDTO;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.core.spi.RoutableTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class SimulateService {

    private final LingRepository lingRepository;
    private final EventBus eventBus;
    private final PermissionService permissionService;
    private final InvocationPipelineEngine pipelineEngine;
    private final LingFrameInfo lingFrameInfo;
    private final LingServiceRegistry lingServiceRegistry;

    /**
     * 模拟资源访问权限校验
     * 这将发起一次完全真实的内核级模拟调用，收集决策追踪。
     * ⚠️ 这里不再走“控制台自己拼一套规则”的旁路逻辑，而是强制借道真实 Pipeline，
     * 否则控制台看到的结果和线上真实治理链就会越跑越不一致。
     */
    public SimulateResultDTO simulateResource(String lingId, String resourceType) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new LingInvocationException(lingId, LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling not active");
        }

        String traceId = LingCallContext.startTrace();

        InvocationContext ctx = InvocationContextBuilder.forSimulation(lingId)
                .traceId(traceId)
                .resourceType(mapResourceType(resourceType))
                .resourceId("simulate:" + resourceType)
                .operation("simulate_" + resourceType)
                .accessType(mapAccessType(resourceType))
                .requiredPermission(mapPermission(resourceType))
                .auditAction("SIMULATE:" + resourceType.toUpperCase())
                .build(lingRepository);

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
                ctx.execution().addTrace(EngineTrace.builder()
                        .source("SimulateService")
                        .action("Dev mode bypass permissions check")
                        .type("WARN")
                        .depth(1)
                        .build());
            }

        } catch (LingInvocationException e) {
            allowed = false;
            message = "Pipeline Rejected: " + e.getMessage();
            ctx.execution()
                    .addTrace(EngineTrace.builder().source("Pipeline")
                            .action("Pipeline rejected invocation: " + e.getKind())
                            .type("FAIL")
                            .depth(1).build());
        } catch (SecurityException e) {
            allowed = false;
            message = "Access Denied: " + e.getMessage();
        } catch (Exception e) {
            allowed = false;
            message = "Execution Failed: " + e.getMessage();
        }

        SimulateResultDTO dto = SimulateResultDTO.builder()
                .traceId(traceId)
                .lingId(lingId)
                .resourceType(resourceType)
                .allowed(allowed)
                .message(message)
                .ruleSource(ctx.governance().getRuleSource())
                .devModeBypass(devBypass)
                .timestamp(System.currentTimeMillis())
                // 暴露 Core 在干跑期间攒下的宝贵探针数据
                .traces(ctx.execution().getTraces() != null ? new ArrayList<>(ctx.execution().getTraces()) : null)
                .build();
        // InvocationContext.obtain() 必须配对 recycle()，否则对象池会泄漏上下文，
        // 残留的 WeakReference 与 attachments 会跨调用污染后续请求
        ctx.recycle();
        return dto;
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
            throw new LingInvocationException(lingId, LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Source ling not active");
        }

        LingRuntime targetRuntime = lingRepository.getRuntime(targetLingId);
        String traceId = LingCallContext.startTrace();

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
            ctx = InvocationContextBuilder.forSimulation(targetLingId)
                    .callerLingId(lingId)
                    .traceId(traceId)
                    .resourceType("IPC")
                    .resourceId(Capabilities.IPC_PREFIX + lingId + "->" + targetLingId)
                    .operation("ipc_call")
                    .accessType(AccessType.EXECUTE)
                    .requiredPermission(Capabilities.ipcCapability(targetLingId))
                    .auditAction("IPC_CALL")
                    .build(lingRepository);

            try {
                // 第 1 阶段：路由预热与基础可用性检查
                publishTrace(traceId, lingId, "  ↳ Pipeline routing...", "IN", 2);

                LingInstance routed = targetRuntime.getInstancePool().getDefault();
                if (routed == null) {
                    throw new LingInvocationException(targetLingId, LingInvocationException.ErrorKind.STATE_REJECTED,
                            "No active instances");
                }
                // 流量统计由 Pipeline 内部 TrafficMetricsFilter 统一处理，
                // 控制台不应绕过 Pipeline 直接修改灵元运行时内部计数

                // 🔥 通过真实 Pipeline 统一入口执行模拟，避免控制台和内核维护两套语义
                pipelineEngine.invoke(ctx);

                allowed = true;
                message = "IPC Call Simulated Success";

                // 检查是否被开发模式豁免
                if (isDevModeBypass(lingId, Capabilities.ipcCapability(targetLingId), AccessType.EXECUTE)) {
                    message += " (⚠️ Dev Mode Bypass)";
                    ctx.execution().addTrace(EngineTrace.builder()
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

        SimulateResultDTO dto = SimulateResultDTO.builder()
                .traceId(traceId)
                .lingId(lingId)
                .targetLingId(targetLingId)
                .resourceType("IPC")
                .allowed(allowed)
                .message(message)
                .traces(ctx != null && ctx.execution().getTraces() != null
                        ? new ArrayList<>(ctx.execution().getTraces())
                        : null)
                .timestamp(System.currentTimeMillis())
                .build();
        // InvocationContext.obtain() 必须配对 recycle()，否则对象池会泄漏上下文，
        // 残留的 WeakReference 与 attachments 会跨调用污染后续请求
        if (ctx != null) {
            ctx.recycle();
        }
        return dto;
    }

    /**
     * 压测单次路由
     * 由前端 setInterval 控制频率，后端每次只执行一次路由
     * <p>
     * 压测本质就是模拟一次路由+调用，因此统一走 {@link InvocationPipelineEngine#invoke}
     * 的 SIMULATION 模式，由 Pipeline 完成路由与统计，收敛治理执行入口。
     * <p>
     * 全契约 L0 路由：压测上下文必须携带<b>裸契约 ID</b> 且不锁定 targetLingId，
     * 否则 {@code ContractProviderRoutingFilter} 的 L0 分支直接放行，pipeline 不会选路，
     * 压测退化为空转（永远 v1=100%）。正确的压测＝高峰期真实走一次「契约 → provider 权重 → 版本/实例」全链路由。
     * <p>
     * 活跃请求数由 {@code LingHealthMetrics.activeRequests} 维护，
     * 由 Pipeline 内部的 {@code TrafficMetricsFilter} 更新，本方法返回时
     * 直接读取运行时池的活跃实例计数兜底。
     */
    public StressResultDTO stressTest(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new LingInvocationException(lingId, LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling not active");
        }

        List<LingInstance> instances = runtime.getInstancePool().getActiveInstances();
        if (instances.isEmpty()) {
            throw new LingInvocationException(lingId, LingInvocationException.ErrorKind.STATE_REJECTED,
                    "No active instances");
        }

        // 解析被测灵元的代表契约：无注册契约则无法对准任何接口，拒绝空转压测
        String contractId = resolveStressContract(lingId);
        if (contractId == null) {
            throw new LingInvocationException(lingId, LingInvocationException.ErrorKind.ROUTE_FAILURE,
                    "Ling has no registered service contract to stress");
        }

        String traceId = LingCallContext.startTrace();
        // 契约级模拟：不锁定 targetLingId，携带裸契约让 L0 provider 路由在全部候选（含灵核）间按权重选路
        // 入口治理事实（accessType + 空 requiredPermission）用于通过 GovernanceDecisionFilter
        // 快速失败守卫：
        // 契约级压测无方法可解析，也未声明任何权限，空 capability 在 dev 模式按「未声明即放行」处理；
        // prod 模式零信任红线会拒绝契约级压测——这是期望语义（无方法级授权验证的干跑不应穿治理）
        InvocationContext ctx = InvocationContextBuilder.forContractSimulation(lingId, contractId)
                .traceId(traceId)
                .accessType(AccessType.EXECUTE)
                .requiredPermission("")
                .build();
        try {
            // 压测走 Pipeline SIMULATION 模式，由 Pipeline 内的
            // ContractProviderRoutingFilter + ProviderWeightRouter + InstanceRoutingFilter
            // 完成路由与选实例
            pipelineEngine.invoke(ctx);

            LingInstance defaultInstance = runtime.getInstancePool().getDefault();
            // 路由结果分类（全契约语义）：
            // NO_ROUTE —— 未选路（候选空等退化场景，压测意义=todo）
            // CORE —— 命中灵核 baseline（provider 无版本）
            // NON_DEFAULT —— 命中非默认版本 provider（迭代新版本/金丝雀）
            // DEFAULT —— 命中默认版本 provider（基线或与默认实例同版本）
            String targetVersion = ctx.getTargetVersion();
            String targetLingId = ctx.getTargetLingId();
            boolean noRoute = targetLingId == null;
            boolean routedToCore = !noRoute && targetVersion == null;
            boolean v2Hit = !noRoute && !routedToCore && defaultInstance != null
                    && !targetVersion.equals(defaultInstance.getDefinition().getVersion());

            // 灵核无版本概念：targetVersion == null 命中灵核时，版本槽用哨兵值 LINGCORE_LING_ID
            String version = targetVersion != null ? targetVersion : LingCoreConstants.LINGCORE_LING_ID;
            String tag = noRoute ? "NO_ROUTE"
                    : (routedToCore ? "CORE"
                            : (v2Hit ? "NON_DEFAULT" : "DEFAULT"));

            publishTrace(traceId, lingId,
                    String.format("→ Routed to: %s (%s)", version, tag), tag, 1);

            // 活跃请求数 = 各活跃实例在途请求数之和（LingInstance.activeRequests AtomicLong 聚合）
            int active = 0;
            try {
                active = runtime.getInstancePool().getActiveInstances().stream()
                        .mapToInt(inst -> (int) inst.getActiveRequestCount())
                        .sum();
            } catch (Exception ignored) {
                // 防御性兜底
            }

            return StressResultDTO.builder()
                    .lingId(lingId)
                    .totalRequests(1)
                    .v1Requests(v2Hit ? 0 : 1)
                    .v2Requests(v2Hit ? 1 : 0)
                    .activeRequests(active)
                    .v1Percent(v2Hit ? 0 : 100)
                    .v2Percent(v2Hit ? 100 : 0)
                    .build();
        } catch (Throwable t) {
            log.warn("Stress test invocation failed for ling {}: {}", lingId, t.getMessage());
            throw t;
        } finally {
            // 🔥 InvocationContext.obtain() 必须配对 recycle()，否则对象池会泄漏上下文，
            // 残留的 WeakReference 与 attachments 会跨调用污染后续请求
            ctx.recycle();
        }
    }

    /**
     * 对指定契约执行单次真实微内核模拟演练调用。
     * <p>
     * 穿透真实的 {@link InvocationPipelineEngine} 流水线与治理拦截器，
    /**
     * 执行单步契约微内核路由演练（默认干跑模式）。
     *
     * @param contractId 契约 ID（如 com.lingframe.ruoyi.contract.ISysStorageService）
     * @return 单次演练步进结果
     */
    public ContractStressStepDTO stressContractStep(String contractId) {
        return stressContractStep(contractId, "DRY_RUN");
    }

    /**
     * 执行单步契约微内核路由演练（支持指定模式）。
     * <p>
     * 模式说明：
     * <ul>
     *   <li>{@code DRY_RUN}：仅跑微内核选路决断逻辑，极速无副作用；</li>
     *   <li>{@code PENETRATION}：真实穿透到目标灵元/灵核容器，触发灵元内部日志打印与真实连通。</li>
     * </ul>
     *
     * @param contractId 契约 ID
     * @param mode 演练模式（DRY_RUN / PENETRATION）
     * @return 单次演练步进结果
     */
    public ContractStressStepDTO stressContractStep(String contractId, String mode) {
        if (lingServiceRegistry == null || contractId == null) {
            throw new IllegalArgumentException("Contract ID or registry not available");
        }
        List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
        if (providers == null || providers.isEmpty()) {
            throw new LingInvocationException(contractId, LingInvocationException.ErrorKind.ROUTE_FAILURE,
                    "No provider registered for contract: " + contractId);
        }

        String modeStr = "PENETRATION".equalsIgnoreCase(mode) ? "PENETRATION" : "DRY_RUN";
        boolean isPenetration = "PENETRATION".equals(modeStr);

        String traceId = LingCallContext.startTrace();
        InvocationContext ctx = InvocationContextBuilder.forContractSimulation("lingcore-app", contractId)
                .traceId(traceId)
                .accessType(AccessType.EXECUTE)
                .requiredPermission("")
                .build();

        long startNs = System.nanoTime();
        try {
            pipelineEngine.invoke(ctx);

            String targetLingId = ctx.getTargetLingId();
            String targetVersion = ctx.getTargetVersion();
            boolean isCore = targetLingId == null || LingCoreConstants.LINGCORE_LING_ID.equals(targetLingId)
                    || targetVersion == null;
            String hitProviderKey = isCore ? LingCoreConstants.LINGCORE_LING_ID : (targetLingId + ":" + targetVersion);
            String displayType = isCore ? "CORE" : "LING";

            // 🔥 真实穿透模式：真实调度到目标灵元/灵核容器的 Bean 实例或探针，并输出灵元自身业务日志
            if (isPenetration) {
                executePenetrationProbe(contractId, isCore, targetLingId, targetVersion);
            }

            long durationNs = System.nanoTime() - startNs;
            double durationMs = durationNs / 1_000_000.0;

            String logAction = String.format("→ [Contract Drill - %s] Contract=[%s] Hit=[%s] Type=[%s] Latency=%.2fms",
                    modeStr, contractId, hitProviderKey, displayType, durationMs);
            publishTrace(traceId, isCore ? LingCoreConstants.LINGCORE_LING_ID : targetLingId, logAction, displayType, 1);
            log.info("[Contract Drill - {}] Contract=[{}], Hit=[{}], Type=[{}], Latency={}ms",
                    modeStr, contractId, hitProviderKey, displayType, String.format("%.2f", durationMs));

            return ContractStressStepDTO.builder()
                    .contractId(contractId)
                    .traceId(traceId)
                    .hitProviderKey(hitProviderKey)
                    .lingId(isCore ? LingCoreConstants.LINGCORE_LING_ID : targetLingId)
                    .version(isCore ? null : targetVersion)
                    .type(displayType)
                    .mode(modeStr)
                    .durationMs(Math.round(durationMs * 100.0) / 100.0)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Throwable t) {
            log.warn("[Contract Drill - {}] Drill step failed for contract {}: {}", modeStr, contractId, t.getMessage());
            throw t;
        } finally {
            ctx.recycle();
        }
    }

    /**
     * 真实穿透探测：在目标灵元或灵核的实例环境中调用统一标准探针方法，触发自身日志。
     */
    private void executePenetrationProbe(String contractId, boolean isCore, String targetLingId, String targetVersion) {
        if (isCore) {
            RoutableTarget coreTarget = lingRepository.getRoutableTarget(LingCoreConstants.LINGCORE_LING_ID);
            if (coreTarget != null && !coreTarget.getReadyInstances().isEmpty()) {
                coreTarget.getReadyInstances().get(0).probe(contractId);
                return;
            }
            log.info("[lingcore-app] [LingProbe] Health probe ping received, Core baseline is ACTIVE, contract: {}", contractId);
            return;
        }

        if (targetLingId == null) {
            return;
        }

        LingRuntime runtime = lingRepository.getRuntime(targetLingId);
        if (runtime == null || !runtime.isAvailable()) {
            log.warn("[{}] [LingProbe] Target runtime not available during penetration", targetLingId);
            return;
        }

        LingInstance instance = runtime.getInstancePool().getDefault();
        if (instance == null || !instance.isReady()) {
            log.warn("[{}] [LingProbe] No ready instance available during penetration", targetLingId);
            return;
        }

        instance.probe(contractId);
    }

    /**
     * 解析被测灵元的代表契约 ID（裸契约名，不含 {@code lingId:} 前缀）。
     * <p>
     * 从该灵元注册的服务 FQSID 中取第一个可路由契约，供压测做全契约 L0 路由入口。
     * 契约声明数据源为 {@link LingServiceRegistry#getServicesByLingId(String)}（元数据层反向索引）。
     *
     * @return 裸契约 ID；灵元未注册任何服务契约或注册表不可用时返回 null
     */
    private String resolveStressContract(String lingId) {
        if (lingServiceRegistry == null || lingId == null) {
            return null;
        }
        List<String> services = lingServiceRegistry.getServicesByLingId(lingId);
        if (services == null || services.isEmpty()) {
            return null;
        }
        for (String fqsid : services) {
            int idx = fqsid.indexOf(':');
            if (idx > 0 && idx < fqsid.length() - 1) {
                return fqsid.substring(idx + 1);
            }
        }
        return null;
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
     * 通过核心引擎执行统一模拟，消除老旧的反射直连逻辑。
     */
    public SimulateResultDTO simulateMethod(String lingId, String className, String methodName,
            AccessType targetAccess) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        if (!runtime.isAvailable()) {
            throw new LingInvocationException(lingId, LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling not active");
        }

        String traceId = LingCallContext.startTrace();

        boolean allowed;
        String message;
        InvocationContext ctx = null;
        boolean devBypass = false;

        try {
            ctx = InvocationContextBuilder.forSimulation(lingId)
                    .traceId(traceId)
                    .resourceType("METHOD")
                    .resourceId(className + "#" + methodName)
                    .operation(methodName)
                    .accessType(targetAccess)
                    .auditAction("SIMULATE:METHOD")
                    .build(lingRepository);
            ctx.setServiceFQSID(lingId + ":" + className);
            ctx.setMethodName(methodName);

            // 🔥 通过真实 Pipeline 统一入口执行模拟推演
            Object result = pipelineEngine.invoke(ctx);

            allowed = true;
            message = "Method " + methodName + " simulated successfully: " + result;

            // 检查开发模式
            String capability = ctx.governance().getRequiredPermission();
            if (capability != null && !capability.trim().isEmpty()) {
                if (isDevModeBypass(lingId, capability, ctx.governance().getAccessType())) {
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

        SimulateResultDTO dto = SimulateResultDTO.builder()
                .traceId(traceId)
                .lingId(lingId)
                .resourceType("METHOD")
                .allowed(allowed)
                .message(message)
                .ruleSource(ctx != null ? ctx.governance().getRuleSource() : null)
                .devModeBypass(devBypass)
                .timestamp(System.currentTimeMillis())
                .traces(ctx != null && ctx.execution().getTraces() != null
                        ? new ArrayList<>(ctx.execution().getTraces())
                        : null)
                .build();
        // InvocationContext.obtain() 必须配对 recycle()，否则对象池会泄漏上下文，
        // 残留的 WeakReference 与 attachments 会跨调用污染后续请求
        if (ctx != null) {
            ctx.recycle();
        }
        return dto;
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
        if (lingFrameInfo == null || !lingFrameInfo.isDevMode()) {
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
