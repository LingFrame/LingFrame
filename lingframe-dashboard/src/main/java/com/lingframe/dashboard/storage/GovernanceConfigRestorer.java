package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.routing.ProviderWeightRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.util.Map;

/**
 * 启动时从 SQLite 恢复治理配置到治理注册表与 ProviderWeightRouter。
 * <p>
 * 内部委托 {@link GovernanceAdminService} 持久化 patch，
 * 不再直接持有 {@code LocalGovernanceRegistry}。
 * <p>
 * 灰度配置（{@code config_type='canary'}）已升级为迁移阶段持久化
 * （{@code config_type='migration'}），恢复时重建 ProviderWeightRouter 的权重覆盖。
 * <p>
 * 使用 {@link InitializingBean} 兼容 SB2/SB3，避免 javax/jakarta.annotation 差异。
 */
@Slf4j
public class GovernanceConfigRestorer implements InitializingBean {

    private final GovernanceStorage governanceStorage;
    private final GovernanceAdminService governanceAdmin;
    private final ProviderWeightRouter providerWeightRouter;
    // 复用 Spring 容器中的单例 ObjectMapper，避免每次恢复都创建新实例
    private final ObjectMapper objectMapper;

    public GovernanceConfigRestorer(GovernanceStorage governanceStorage,
                                    GovernanceAdminService governanceAdmin,
                                    ProviderWeightRouter providerWeightRouter,
                                    ObjectMapper objectMapper) {
        this.governanceStorage = governanceStorage;
        this.governanceAdmin = governanceAdmin;
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

            int restored = 0;
            for (Map.Entry<String, Map<String, String>> entry : allConfigs.entrySet()) {
                String lingId = entry.getKey();
                Map<String, String> configs = entry.getValue();
                boolean hasPatch = false;

                // 恢复 provider 权重覆盖（迁移阶段持久化，config_type='migration'）
                // 兼容旧 canary JSON：将其中的 percent 映射为权重覆盖
                String migrationJson = configs.get("migration");
                if (migrationJson == null) {
                    migrationJson = configs.get("canary"); // 向后兼容
                }
                if (migrationJson != null && providerWeightRouter != null) {
                    try {
                        Map<?, ?> migrationData = objectMapper.readValue(migrationJson, Map.class);
                        Object percentObj = migrationData.get("percent");
                        Object canaryVersionObj = migrationData.get("canaryVersion");
                        Object contractIdObj = migrationData.get("contractId");
                        Object oldCandidateObj = migrationData.get("oldCandidate");
                        Object newCandidateObj = migrationData.get("newCandidate");
                        if (percentObj != null && contractIdObj != null
                                && oldCandidateObj != null && newCandidateObj != null) {
                            // 迁移阶段格式：含候选键 + 百分比，转权重
                            int newWeight = ((Number) percentObj).intValue();
                            int oldWeight = 100 - newWeight;
                            providerWeightRouter.setProviderWeight(
                                    String.valueOf(contractIdObj),
                                    String.valueOf(newCandidateObj), newWeight);
                            providerWeightRouter.setProviderWeight(
                                    String.valueOf(contractIdObj),
                                    String.valueOf(oldCandidateObj), oldWeight);
                            hasPatch = true;
                        } else if (percentObj != null && canaryVersionObj != null) {
                            // 兼容旧 canary JSON：无 contractId/oldCandidate/newCandidate 三键,
                            // 用 lingId 命中契约键、canaryVersion �命中新候选、灵核 baseline 命中旧候选,
                            // percent 映为新候选权重、100-percent 映为灵核权重,避免重启后灰度切流静默丢失
                            int newWeight = ((Number) percentObj).intValue();
                            int oldWeight = 100 - newWeight;
                            String newCandidate = String.valueOf(canaryVersionObj);
                            String oldCandidate = LingCoreConstants.LINGCORE_LING_ID;
                            providerWeightRouter.setProviderWeight(lingId, newCandidate, newWeight);
                            providerWeightRouter.setProviderWeight(lingId, oldCandidate, oldWeight);
                            log.warn("Legacy canary config restored as migration weights: lingId={}, newCandidate={}, oldCandidate=lingcore",
                                    lingId, newCandidate);
                            hasPatch = true;
                        } else {
                            // 不可恢复的旧格式（缺 percent 或 canaryVersion）:计数不增,告警可观测
                            log.warn("Legacy canary/migration JSON unreadable, skipped: lingId={}", lingId);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to restore migration configuration: lingId={}", lingId, e);
                    }
                }

                // 合并 invocation / permission 配置为 GovernancePolicy patch
                GovernancePolicy mergedPatch = null;
                for (Map.Entry<String, String> configEntry : configs.entrySet()) {
                    String key = configEntry.getKey();
                    if ("canary".equals(key) || "migration".equals(key)) {
                        continue; // 迁移阶段已单独处理
                    }
                    try {
                        GovernancePolicy policy = governanceStorage.safeDeserialize(configEntry.getValue());
                        if (mergedPatch == null) {
                            mergedPatch = policy;
                        } else {
                            mergedPatch = GovernancePolicy.merge(mergedPatch, policy);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to restore governance configuration, skipped: lingId={}, type={}", lingId, key, e);
                    }
                }

                if (mergedPatch != null) {
                    governanceAdmin.persistPolicyPatch(lingId, mergedPatch);
                    hasPatch = true;
                }

                if (hasPatch) {
                    restored++;
                }
            }
            log.info("Governance configuration restoration completed: {} lings", restored);
        } catch (Exception e) {
            log.warn("Failed to restore governance configuration (does not affect startup)", e);
        }
    }
}
