package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.InvocationConfigDTO;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationPolicyPrefillFilter;
import com.lingframe.core.pipeline.ResilienceGovernanceFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 治理管理服务。
 * <p>
 * 治理内核的正式职责门面：把散在 dashboard / runtime 业务类中的
 * 「策略合并 + patch 管理 + 权限同步」收束到一处，避免外围模块直接操作
 * {@link LocalGovernanceRegistry} 与 {@link GovernancePermissionSynchronizer}。
 * <p>
 * 公开方法只接受 / 返回 {@code api} 层 DTO 与灵珑只读视图，
 * 不暴露治理内核内部注册表或静态同步器。
 *
 * @see GovernancePolicy#merge(GovernancePolicy, GovernancePolicy)
 */
@Slf4j
public class GovernanceAdminService {

    private final LingRepository lingRepository;
    private final LocalGovernanceRegistry patchRegistry;
    private final PermissionService permissionService;

    /**
     * 唯一构造器。
     *
     * @param lingRepository 灵元仓库，可为 null
     * @param patchRegistry 治理补丁注册表，可为 null
     * @param permissionService 权限服务，不可为 null
     */
    public GovernanceAdminService(LingRepository lingRepository,
                                   LocalGovernanceRegistry patchRegistry,
                                   PermissionService permissionService) {
        this.lingRepository = lingRepository;
        this.patchRegistry = patchRegistry;
        this.permissionService = permissionService;
    }

    /**
     * 查询灵元当前生效策略。
     * <p>
     * 生效策略 = 静态策略（ling.yml 声明）与动态补丁合并后的结果。
     * 任一为空则直接返回另一份的拷贝；两空返回 null。
     *
     * @param lingId 灵元 ID
     * @return 生效策略拷贝，可能为 null
     */
    public GovernancePolicy getEffectivePolicy(String lingId) {
        GovernancePolicy staticPolicy = getStaticPolicy(lingId);
        GovernancePolicy patch = patchRegistry == null ? null : patchRegistry.getPatch(lingId);
        if (staticPolicy == null && patch == null) {
            return null;
        }
        return GovernancePolicy.merge(staticPolicy, patch);
    }

    /**
     * 获取可修改的补丁策略副本。
     * <p>
     * 调用方可安全修改返回对象，不影响注册表内部真源；
     * 修改完成后通过 {@link #persistPolicyPatch} 落库。
     *
     * @param lingId 灵元 ID
     * @return 补丁策略拷贝；无补丁时返回空策略对象
     */
    public GovernancePolicy getPatchForUpdate(String lingId) {
        GovernancePolicy patch = patchRegistry == null ? null : patchRegistry.getPatch(lingId);
        return patch == null ? new GovernancePolicy() : patch.copy();
    }

    /**
     * 查询全部灵元的补丁快照（只读）。
     * <p>
     * 替代 Dashboard 展示端直接持有 {@code LocalGovernanceRegistry} 调 {@code getAllPatches()} 的越界路径。
     * 返回的是补丁真源的拷贝视图，外部修改不回写注册表。
     *
     * @return lingId -> 补丁策略拷贝 的只读映射；无注册表时返回空映射
     */
    public Map<String, GovernancePolicy> getAllPatches() {
        if (patchRegistry == null) {
            return Collections.emptyMap();
        }
        Map<String, GovernancePolicy> snapshot = patchRegistry.getAllPatches();
        if (snapshot == null || snapshot.isEmpty()) {
            return Collections.emptyMap();
        }
        // 拷贝避免外部修改污染真源
        Map<String, GovernancePolicy> copy = new HashMap<>();
        for (Map.Entry<String, GovernancePolicy> entry : snapshot.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().copy());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 持久化补丁并同步运行时权限表。
     * <p>
     * 内部收束两步：
     * <ol>
     *   <li>更新 {@link LocalGovernanceRegistry} 的补丁真源</li>
     *   <li>调用 {@link GovernancePermissionSynchronizer#syncPolicy} 同步权限表</li>
     * </ol>
     * 调用方不再需要直接持有注册表或静态同步器。
     *
     * @param lingId 灵元 ID
     * @param patch  待落库补丁，不能为 null
     */
    public void persistPolicyPatch(String lingId, GovernancePolicy patch) {
        if (lingId == null || lingId.isEmpty()) {
            log.warn("[Governance] Skip persisting patch with blank lingId");
            return;
        }
        if (patchRegistry == null) {
            log.warn("[Governance] Patch registry not configured, cannot persist patch for ling {}", lingId);
            return;
        }
        patchRegistry.updatePatch(lingId, patch);
        GovernancePermissionSynchronizer.syncPolicy(lingId, getEffectivePolicy(lingId), permissionService);
    }

    /**
     * 取灵元静态策略（ling.yml 声明，不含补丁）。
     * <p>
     * 从默认实例的 {@code LingDefinition.governance} 读取并返回拷贝，
     * 避免外部修改污染真源。
     */
    private GovernancePolicy getStaticPolicy(String lingId) {
        if (lingRepository == null) {
            return null;
        }
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return null;
        }
        LingInstance instance = runtime.getInstancePool().getDefault();
        if (instance == null || instance.getDefinition() == null) {
            return null;
        }
        GovernancePolicy governance = instance.getDefinition().getGovernance();
        return governance == null ? null : governance.copy();
    }

    /**
     * 统一下发调用治理配置。
     * <p>
     * 治理策略唯一真源是 {@link GovernancePolicy.InvocationPolicy}（静态 ling.yml 声明 + 动态 patch 合并）。
     * 本方法只写 patch 一处，由 {@link InvocationPolicyPrefillFilter}
     * 在 RESILIENCE 阶段之前把灵元级 effective policy 预填到 {@code ctx.governance()}，
     * 弹性治理组件（{@link ResilienceGovernanceFilter}）通过 ctx 读取治理意图，
     * 无需同步 {@code LingRuntimeConfig} 底座，也无需驱逐缓存。
     * <p>
     * 字段语义与 {@link GovernancePolicy.InvocationPolicy} 一一对应，
     * null 字段表示「不动」。
     *
     * @param lingId 灵元 ID
     * @param config 调用治理配置下发视图，不能为 null
     */
    public void updateInvocationConfig(String lingId, InvocationConfigDTO config) {
        if (lingId == null || lingId.isEmpty()) {
            log.warn("[Governance] Skip updating invocation config with blank lingId");
            return;
        }
        if (config == null) {
            throw new IllegalArgumentException("InvocationConfigDTO must not be null");
        }
        if (lingRepository == null) {
            log.warn("[Governance] Repository not configured, cannot update invocation config for ling {}", lingId);
            return;
        }

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            log.warn("[Governance] Cannot update invocation config: ling {} not loaded", lingId);
            return;
        }
        LingInstance instance = runtime.getInstancePool().getDefault();
        if (instance == null || instance.getDefinition() == null) {
            log.warn("[Governance] Cannot update invocation config: ling {} has no default instance definition", lingId);
            return;
        }

        // 写入补丁层（治理策略动态修改的唯一真源），而非静态 definition。
        // 静态策略来自 ling.yml 声明，运行时下发应进入 patch，由 getEffectivePolicy 经 merge 计算生效。
        // 预填充 filter 会在每次请求时读取最新 effective policy 预填到 ctx.governance()，
        // 弹性组件通过 ctx 读取，无需同步 LingRuntimeConfig 底座，也无需驱逐缓存。
        GovernancePolicy patch = getPatchForUpdate(lingId);
        GovernancePolicy.InvocationPolicy invocation = patch.getInvocation();
        if (invocation == null) {
            invocation = new GovernancePolicy.InvocationPolicy();
            patch.setInvocation(invocation);
        }
        if (config.getTimeoutMs() != null) invocation.setTimeoutMs(config.getTimeoutMs());
        if (config.getRateLimitPerSecond() != null) invocation.setRateLimitPerSecond(config.getRateLimitPerSecond());
        if (config.getMaxConcurrentThreads() != null) invocation.setMaxConcurrentThreads(config.getMaxConcurrentThreads());
        if (config.getRetryCount() != null) invocation.setRetryCount(config.getRetryCount());
        if (config.getFallbackValue() != null) invocation.setFallbackValue(config.getFallbackValue());
        if (config.getCpuBudgetMsPerMinute() != null) invocation.setCpuBudgetMsPerMinute(config.getCpuBudgetMsPerMinute());
        if (config.getMemoryBudgetMb() != null) invocation.setMemoryBudgetMb(config.getMemoryBudgetMb());
        persistPolicyPatch(lingId, patch);

        log.info("[Governance] Invocation config updated for ling {}", lingId);
    }
}
