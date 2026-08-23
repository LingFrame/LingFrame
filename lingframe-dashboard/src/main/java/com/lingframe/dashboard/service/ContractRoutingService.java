package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.exception.RoutingArchitectureViolationException;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.routing.MigrationPhase;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.routing.ProviderDescriptor;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.dashboard.dto.ContractRoutingDTO;
import com.lingframe.dashboard.dto.ProviderWeightDTO;
import com.lingframe.dashboard.storage.GovernanceStorage;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 契约路由策略服务。
 * <p>
 * Dashboard 契约路由页面的后端业务层，负责：
 * <ol>
 *   <li>列出所有有多 provider 的契约</li>
 *   <li>查询某契约的 provider 列表 + 当前权重</li>
 *   <li>下发权重覆盖（通过 {@link ProviderWeightRouter}）</li>
 *   <li>一键回滚到灵核 100%（灵核 baseline 权重 100，灵元权重 0）</li>
 * </ol>
 * <p>
 * 权重来源语义：
 * <ul>
 *   <li>{@code registeredWeight}——注册时携带的初始权重，不可变</li>
 *   <li>{@code overrideWeight}——Dashboard 运行期下发，null 表示未覆盖</li>
 *   <li>{@code effectiveWeight}——overrideWeight 非空时取它，否则取 registeredWeight</li>
 * </ul>
 * <p>
 * 灵核识别：通过 {@link LingCoreConstants#LINGCORE_LING_ID} 常量比较 lingId，
 * 仅用于 Dashboard 运维视图（「一键回滚到灵核」），不参与路由决策。
 * 路由层只认 weight 和方法资格，不引用实现方身份。
 */
@Slf4j
public class ContractRoutingService {

    private final LingServiceRegistry lingServiceRegistry;
    private final ProviderWeightRouter providerWeightRouter;
    /** 迁移状态机持有者；null 时降级为纯权重下发（native/test 场景） */
    private final MigrationStateHolder migrationStateHolder;

    /** 治理配置存储（可选，用于持久化权重到 SQLite） */
    @Setter
    private GovernanceStorage governanceStorage;

    @Setter
    private ObjectMapper objectMapper;

    public ContractRoutingService(LingServiceRegistry lingServiceRegistry,
            ProviderWeightRouter providerWeightRouter) {
        this(lingServiceRegistry, providerWeightRouter, null);
    }

    public ContractRoutingService(LingServiceRegistry lingServiceRegistry,
            ProviderWeightRouter providerWeightRouter,
            MigrationStateHolder migrationStateHolder) {
        this.lingServiceRegistry = lingServiceRegistry;
        this.providerWeightRouter = providerWeightRouter;
        this.migrationStateHolder = migrationStateHolder;
    }

    /**
     * 列出所有有多 provider 的契约 ID。
     *
     * @return 有 ≥2 个 provider 的契约 ID 列表；无任何注册时返回空列表
     */
    public List<String> listMultiProviderContracts() {
        Set<String> allContracts = lingServiceRegistry.getAllContractIds();
        return allContracts.stream()
                .filter(cid -> lingServiceRegistry.getProvidersByContractId(cid).size() >= 2)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 查询某契约的 provider 列表 + 权重配置。
     *
     * @param contractId 契约 ID
     * @return 路由策略 DTO；契约未注册时返回空 providers 列表
     */
    public ContractRoutingDTO getContractRouting(String contractId) {
        List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
        List<ProviderWeightDTO> providerDtos = new ArrayList<>(providers.size());

        int coreEffective = 0;
        int lingEffective = 0;

        for (ProviderDescriptor desc : providers) {
            // providerKey 与写侧注册键一致（灵元 lingId:version / 灵核 lingcore-app），裸 lingId 会导致权重覆盖命中
            Integer override = providerWeightRouter.getOverrideWeight(contractId, desc.providerKey());
            int effective = computeEffectiveWeight(desc, override);
            boolean isCore = isCoreBaseline(desc.getLingId());
            providerDtos.add(ProviderWeightDTO.builder()
                    .lingId(desc.getLingId())
                    .version(desc.getVersion())
                    .coreBaseline(isCore)
                    .registeredWeight(desc.getWeight())
                    .overrideWeight(override)
                    .effectiveWeight(effective)
                    .build());

            if (isCore) {
                coreEffective += effective;
            } else {
                lingEffective += effective;
            }
        }

        return ContractRoutingDTO.builder()
                .contractId(contractId)
                .providers(providerDtos)
                .multiProvider(providers.size() >= 2)
                .coreEffectiveWeight(coreEffective)
                .lingEffectiveWeight(lingEffective)
                .build();
    }

    /**
     * 设置某契约下指定 provider 的权重。
     * <p>
     * 权重值会被 clamp 到 [0, 100]。
     *
     * @param contractId 契约 ID
     * @param lingId     提供方灵元/灵核 ID
     * @param weight     新权重 0-100
     */
    /**
     * 设置某契约下指定 provider 的权重。
     * <p>
     * 入参 {@code providerKey} 必须与路由读路径键化一致——灵元恒为 {@code lingId:version}，
     * 灵核为裸 {@code lingcore-app}，即 {@link ProviderDescriptor#providerKey()}。
     * 传错形（如灵元侧传裸 lingId）会致权重落键错位、读路径静默丢失。
     *
     * @param contractId  契约 ID
     * @param providerKey 提供方路由键（lingId:version 或 lingcore-app）
     * @param weight      新权重 0-100
     */
    public void setProviderWeight(String contractId, String providerKey, int weight) {
        providerWeightRouter.setProviderWeight(contractId, providerKey, weight);
        log.info("[ContractRouting] Weight updated: contract=[{}], provider=[{}], weight=[{}]",
                contractId, providerKey, weight);
        persistWeights(contractId);
    }

    /**
     * 一键回滚到灵核 100%。
     * <p>
     * 将该契约下灵核 baseline provider 权重设为 100，灵元 provider 权重设为 0。
     * 显式设置覆盖确保即使无灵核 provider 也能保持一致的「灵核优先」语义。
     * <p>
     * 与迁移状态机联动：若当前为 MIGRATING 态，回滚权重同时触发
     * {@link MigrationStateHolder#rollbackPhaseTransition} 收口状态机，
     * 避免权重归零但状态机仍残留 MIGRATING 的不一致。
     *
     * @param contractId 契约 ID
     */
    public void rollbackToCore(String contractId) {
        List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
        for (ProviderDescriptor desc : providers) {
            int targetWeight = isCoreBaseline(desc.getLingId()) ? 100 : 0;
            providerWeightRouter.setProviderWeight(contractId, desc.providerKey(), targetWeight);
        }
        // 状态机联动：MIGRATING 回滚到 CORE_EXCLUSIVE；ITERATING 不由此入口处理（迭代回滚走 rollbackTransition）
        if (migrationStateHolder != null) {
            MigrationPhase phase = migrationStateHolder.getPhase(contractId);
            if (phase == MigrationPhase.MIGRATING) {
                migrationStateHolder.rollbackPhaseTransition(contractId);
            }
        }
        log.info("[ContractRouting] Rollback to core 100%: contract=[{}], providers=[{}]",
                contractId, providers.size());
        persistWeights(contractId);
    }

    /**
     * 持久化指定契约的权重覆盖配置到 GovernanceStorage。
     */
    private void persistWeights(String contractId) {
        if (governanceStorage == null || contractId == null) {
            return;
        }
        try {
            Map<String, Integer> weights = providerWeightRouter.getOverrideWeights(contractId);
            if (weights == null || weights.isEmpty()) {
                governanceStorage.deleteRoutingWeightConfig(contractId);
            } else {
                ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
                governanceStorage.saveRoutingWeightConfig(contractId, mapper.writeValueAsString(weights));
            }
        } catch (Exception e) {
            log.warn("Failed to persist routing weights for contract {}: {}", contractId, e.getMessage());
        }
    }

    // ==================== 相变控制（与 MigrationStateHolder 联动） ====================

    /**
     * 发起迁移：灵核独占 → 灵元接管。
     * <p>
     * 前置条件：当前阶段为 {@link MigrationPhase#CORE_EXCLUSIVE}。
     * 调用后契约进入 MIGRATING 态，二元权重路由生效。
     *
     * @param contractId   契约 ID
     * @param coreProvider 灵核 provider 键（如 {@code lingcore-app})
     * @param lingProvider 灵元 provider 键（裸 {@code lingId})
     * @throws RoutingArchitectureViolationException 非独占态发起迁移
     */
    public void startMigration(String contractId, String coreProvider, String lingProvider) {
        if (migrationStateHolder == null) {
            throw new RoutingArchitectureViolationException("MigrationStateHolder not configured");
        }
        migrationStateHolder.startMigration(contractId, coreProvider, lingProvider);
        log.info("[ContractRouting] Migration started: contract=[{}] core=[{}] ling=[{}]",
                contractId, coreProvider, lingProvider);
    }

    /**
     * 发起迭代：灵元独占 → 灵元新版本接管。
     * <p>
     * 前置条件：当前阶段为 {@link MigrationPhase#LING_EXCLUSIVE}。
     * 调用后契约进入 ITERATING 态，二元版本权重路由生效。
     *
     * @param contractId    契约 ID
     * @param oldLing       旧灵元 provider 键（裸 {@code lingId}）
     * @param newLing       新灵元 provider 键（{@code lingId:version}）
     * @throws RoutingArchitectureViolationException 非灵元独占态发起迭代
     */
    public void startIteration(String contractId, String oldLing, String newLing) {
        if (migrationStateHolder == null) {
            throw new RoutingArchitectureViolationException("MigrationStateHolder not configured");
        }
        migrationStateHolder.startIteration(contractId, oldLing, newLing);
        log.info("[ContractRouting] Iteration started: contract=[{}] old=[{}] new=[{}]",
                contractId, oldLing, newLing);
    }

    /**
     * 显式确认相变完成。
     * <p>
     * 前置条件：当前为二元候选态（MIGRATING / ITERATING）+ 退出方候选活跃请求数为 0（排空校验）。
     * 调用后契约进入 LING_EXCLUSIVE 态。
     *
     * @param contractId 契约 ID
     * @param drainOk    排空校验结果（退出方 activeRequests == 0）
     * @throws RoutingArchitectureViolationException 非二元态确认或排空未通过
     */
    public void confirmTransition(String contractId, boolean drainOk) {
        if (migrationStateHolder == null) {
            throw new RoutingArchitectureViolationException("MigrationStateHolder not configured");
        }
        migrationStateHolder.confirmPhaseTransition(contractId, drainOk);
        log.info("[ContractRouting] Phase transition confirmed: contract=[{}] drainOk=[{}]",
                contractId, drainOk);
    }

    /**
     * 回滚相变。
     * <p>
     * MIGRATING 回滚 → CORE_EXCLUSIVE（退回灵核）；ITERATING 回滚 → LING_EXCLUSIVE（退回旧灵元）。
     *
     * @param contractId 契约 ID
     * @throws RoutingArchitectureViolationException 非二元态回滚
     */
    public void rollbackTransition(String contractId) {
        if (migrationStateHolder == null) {
            throw new RoutingArchitectureViolationException("MigrationStateHolder not configured");
        }
        migrationStateHolder.rollbackPhaseTransition(contractId);
        log.info("[ContractRouting] Phase transition rolled back: contract=[{}]", contractId);
    }

    /**
     * 计算生效权重：override 非空时取 override，否则取注册时初始 weight。
     */
    private int computeEffectiveWeight(ProviderDescriptor desc, Integer override) {
        if (override != null) {
            return Math.max(0, Math.min(100, override));
        }
        return desc.getWeight();
    }

    /**
     * 判定是否灵核 baseline：lingId == LingCoreConstants.LINGCORE_LING_ID。
     * <p>
     * 仅 Dashboard 运维视图用，不参与路由决策。
     */
    private boolean isCoreBaseline(String lingId) {
        return LingCoreConstants.LINGCORE_LING_ID.equals(lingId);
    }
}

