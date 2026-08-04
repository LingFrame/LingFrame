package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.routing.MigrationPhase;
import com.lingframe.core.routing.MigrationStateHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.util.Map;

/**
 * 启动时从 SQLite 恢复治理配置到治理注册表与 MigrationStateHolder。
 * <p>
 * 内部委托 {@link GovernanceAdminService} 持久化 patch，
 * 不再直接持有 {@code LocalGovernanceRegistry}。
 * <p>
 * 迁移阶段配置（{@code config_type='migration'}）恢复语义：
 * <ul>
 *   <li>新格式（{@code phase/oldCandidate/newCandidate}，由 {@link MigrationStateHolder}
 *       相变时落盘）→ 重建 {@link MigrationStateHolder} 阶段；权重覆盖为运行期内存态，
 *       不持久化，重启后路由回到注册默认权重，需运维重新下发切流比例</li>
 * </ul>
 * <p>
 * 使用 {@link InitializingBean} 兼容 SB2/SB3，避免 javax/jakarta.annotation 差异。
 */
@Slf4j
public class GovernanceConfigRestorer implements InitializingBean {

    private final GovernanceStorage governanceStorage;
    private final GovernanceAdminService governanceAdmin;
    /** 迁移状态机持有者；null 时（dashboard 独立运行/测试）跳过阶段重建 */
    private final MigrationStateHolder migrationStateHolder;
    // 复用 Spring 容器中的单例 ObjectMapper，避免每次恢复都创建新实例
    private final ObjectMapper objectMapper;

    public GovernanceConfigRestorer(GovernanceStorage governanceStorage,
                                    GovernanceAdminService governanceAdmin,
                                    ObjectMapper objectMapper) {
        this(governanceStorage, governanceAdmin, null, objectMapper);
    }

    public GovernanceConfigRestorer(GovernanceStorage governanceStorage,
                                    GovernanceAdminService governanceAdmin,
                                    MigrationStateHolder migrationStateHolder,
                                    ObjectMapper objectMapper) {
        this.governanceStorage = governanceStorage;
        this.governanceAdmin = governanceAdmin;
        this.migrationStateHolder = migrationStateHolder;
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

                // 恢复迁移阶段配置（config_type='migration'，新格式 phase/oldCandidate/newCandidate）
                String migrationJson = configs.get(GovernanceConfigTypes.MIGRATION);
                if (migrationJson != null) {
                    try {
                        Map<?, ?> migrationData = objectMapper.readValue(migrationJson, Map.class);
                        Object phaseObj = migrationData.get("phase");
                        Object oldCandidateObj = migrationData.get("oldCandidate");
                        Object newCandidateObj = migrationData.get("newCandidate");

                        if (phaseObj != null) {
                            // 新格式：MigrationStateHolder 相变时落盘（phase/oldCandidate/newCandidate），
                            // 契约号存于表主键 ling_id 而非 JSON 内
                            restorePhase(
                                    lingId,
                                    MigrationPhase.valueOf(String.valueOf(phaseObj)),
                                    stringOrNull(oldCandidateObj),
                                    stringOrNull(newCandidateObj));
                            hasPatch = true;
                        } else {
                            log.warn("Migration config unreadable (missing phase), skipped: lingId={}", lingId);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to restore migration configuration: lingId={}", lingId, e);
                    }
                }

                // 合并 invocation / permission 配置为 GovernancePolicy patch
                GovernancePolicy mergedPatch = null;
                for (Map.Entry<String, String> configEntry : configs.entrySet()) {
                    String key = configEntry.getKey();
                    if (GovernanceConfigTypes.MIGRATION.equals(key)) {
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