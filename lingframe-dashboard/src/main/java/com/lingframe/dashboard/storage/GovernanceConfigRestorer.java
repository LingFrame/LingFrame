package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.routing.MigrationPhase;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.routing.ProviderWeightRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.util.Map;

/**
 * 启动时从 SQLite 恢复治理配置到治理注册表、MigrationStateHolder 及 ProviderWeightRouter。
 * <p>
 * 内部委托 {@link GovernanceAdminService} 持久化 patch，
 * 不再直接持有 {@code LocalGovernanceRegistry}。
 * <p>
 * 使用 {@link InitializingBean} 兼容 SB2/SB3，避免 javax/jakarta.annotation 差异。
 */
@Slf4j
public class GovernanceConfigRestorer implements InitializingBean {

    private final GovernanceStorage governanceStorage;
    private final GovernanceAdminService governanceAdmin;
    /** 迁移状态机持有者；null 时（dashboard 独立运行/测试）跳过阶段重建 */
    private final MigrationStateHolder migrationStateHolder;
    /** 权重路由器；null 时跳过权重恢复 */
    private final ProviderWeightRouter providerWeightRouter;
    // 复用 Spring 容器中的单例 ObjectMapper，避免每次恢复都创建新实例
    private final ObjectMapper objectMapper;

    public GovernanceConfigRestorer(GovernanceStorage governanceStorage,
                                    GovernanceAdminService governanceAdmin,
                                    ObjectMapper objectMapper) {
        this(governanceStorage, governanceAdmin, null, null, objectMapper);
    }

    public GovernanceConfigRestorer(GovernanceStorage governanceStorage,
                                    GovernanceAdminService governanceAdmin,
                                    MigrationStateHolder migrationStateHolder,
                                    ObjectMapper objectMapper) {
        this(governanceStorage, governanceAdmin, migrationStateHolder, null, objectMapper);
    }

    public GovernanceConfigRestorer(GovernanceStorage governanceStorage,
                                    GovernanceAdminService governanceAdmin,
                                    MigrationStateHolder migrationStateHolder,
                                    ProviderWeightRouter providerWeightRouter,
                                    ObjectMapper objectMapper) {
        this.governanceStorage = governanceStorage;
        this.governanceAdmin = governanceAdmin;
        this.migrationStateHolder = migrationStateHolder;
        this.providerWeightRouter = providerWeightRouter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterPropertiesSet() {
        restore();
    }

    public void restore() {
        try {
            Map<String, Map<String, String>> allConfigs = governanceStorage.loadAllConfigs();
            if (allConfigs.isEmpty()) {
                log.info("No persisted governance configurations to restore");
                return;
            }

            int restoredPolicies = 0;
            int restoredPhases = 0;
            int restoredWeights = 0;

            for (Map.Entry<String, Map<String, String>> entry : allConfigs.entrySet()) {
                String targetKey = entry.getKey();
                Map<String, String> configs = entry.getValue();

                // 1. 恢复契约级迁移阶段配置（key 为 contractId，config_type='migration'）
                String migrationJson = configs.get(GovernanceConfigTypes.MIGRATION);
                if (migrationJson != null) {
                    try {
                        Map<?, ?> migrationData = objectMapper.readValue(migrationJson, Map.class);
                        Object phaseObj = migrationData.get("phase");
                        Object oldCandidateObj = migrationData.get("oldCandidate");
                        Object newCandidateObj = migrationData.get("newCandidate");

                        if (phaseObj != null) {
                            restorePhase(
                                    targetKey,
                                    MigrationPhase.valueOf(String.valueOf(phaseObj)),
                                    stringOrNull(oldCandidateObj),
                                    stringOrNull(newCandidateObj));
                            restoredPhases++;
                        } else {
                            log.warn("Migration config unreadable (missing phase), skipped: contractId={}", targetKey);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to restore migration configuration: contractId={}", targetKey, e);
                    }
                }

                // 2. 恢复契约级路由权重配置（key 为 contractId，config_type='routing_weight'）
                String routingWeightJson = configs.get(GovernanceConfigTypes.ROUTING_WEIGHT);
                if (routingWeightJson != null && providerWeightRouter != null) {
                    try {
                        Map<?, ?> weightData = objectMapper.readValue(routingWeightJson, Map.class);
                        for (Map.Entry<?, ?> we : weightData.entrySet()) {
                            String providerKey = String.valueOf(we.getKey());
                            int weight = ((Number) we.getValue()).intValue();
                            providerWeightRouter.setProviderWeight(targetKey, providerKey, weight);
                        }
                        log.info("Restored routing weights for contract {}: {}", targetKey, routingWeightJson);
                        restoredWeights++;
                    } catch (Exception e) {
                        log.warn("Failed to restore routing weights for contract {}: {}", targetKey, e);
                    }
                }

                // 3. 恢复灵元级治理策略配置（key 为 lingId，config_type='invocation'/'permission'）
                GovernancePolicy mergedPatch = null;
                for (Map.Entry<String, String> configEntry : configs.entrySet()) {
                    String type = configEntry.getKey();
                    if (GovernanceConfigTypes.MIGRATION.equals(type) || GovernanceConfigTypes.ROUTING_WEIGHT.equals(type)) {
                        continue; // 契约维度的迁移阶段与路由权重已单独处理
                    }
                    try {
                        GovernancePolicy policy = governanceStorage.safeDeserialize(configEntry.getValue());
                        if (mergedPatch == null) {
                            mergedPatch = policy;
                        } else {
                            mergedPatch = GovernancePolicy.merge(mergedPatch, policy);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to restore governance configuration, skipped: lingId={}, type={}", targetKey, type, e);
                    }
                }

                if (mergedPatch != null) {
                    governanceAdmin.persistPolicyPatch(targetKey, mergedPatch);
                    restoredPolicies++;
                }
            }
            log.info("Governance configuration restoration completed: {} ling policies, {} migration phases, {} contract routing weights",
                    restoredPolicies, restoredPhases, restoredWeights);
        } catch (Exception e) {
            log.warn("Failed to restore governance configuration (does not affect startup)", e);
        }
    }

    /**
     * 按持久化的新格式重建迁移阶段。
     * <p>
     * 校验 phase 值合法性——非法枚举值不会抛出诡异错误，直接告警跳过。
     */
    private void restorePhase(String contractId, MigrationPhase phase,
                              String oldCandidate, String newCandidate) {
        if (migrationStateHolder == null) {
            log.warn("MigrationStateHolder not configured, skipped phase restore: contract={} phase={}",
                    contractId, phase);
            return;
        }
        try {
            migrationStateHolder.restorePhase(contractId, phase, oldCandidate, newCandidate);
        } catch (Exception e) {
            log.warn("Failed to restore migration phase: contract={} phase={}", contractId, phase, e);
        }
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}