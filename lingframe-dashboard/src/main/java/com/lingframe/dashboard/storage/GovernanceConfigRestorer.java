package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.router.CanaryRouter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import java.util.Map;

/**
 * 启动时从 SQLite 恢复治理配置到治理注册表和 CanaryRouter。
 * <p>
 * 内部委托 {@link GovernanceAdminService} 持久化 patch，
 * 不再直接持有 {@code LocalGovernanceRegistry}。
 */
@Slf4j
public class GovernanceConfigRestorer {

    private final GovernanceStorage governanceStorage;
    private final GovernanceAdminService governanceAdmin;
    private final CanaryRouter canaryRouter;
    // 复用 Spring 容器中的单例 ObjectMapper，避免每次恢复都创建新实例
    private final ObjectMapper objectMapper;

    public GovernanceConfigRestorer(GovernanceStorage governanceStorage,
                                    GovernanceAdminService governanceAdmin,
                                    CanaryRouter canaryRouter,
                                    ObjectMapper objectMapper) {
        this.governanceStorage = governanceStorage;
        this.governanceAdmin = governanceAdmin;
        this.canaryRouter = canaryRouter;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
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

                // 恢复灰度配置（canary 类型是独立的 JSON，不是 GovernancePolicy）
                String canaryJson = configs.get("canary");
                if (canaryJson != null) {
                    try {
                        Map<?, ?> canaryData = objectMapper.readValue(canaryJson, Map.class);
                        int percent = ((Number) canaryData.get("percent")).intValue();
                        String canaryVersion = (String) canaryData.get("canaryVersion");
                        canaryRouter.setCanaryConfig(lingId, percent, canaryVersion);
                        hasPatch = true;
                    } catch (Exception e) {
                        log.warn("Failed to restore canary configuration: lingId={}", lingId, e);
                    }
                }

                // 合并 invocation / permission 配置为 GovernancePolicy patch
                GovernancePolicy mergedPatch = null;
                for (Map.Entry<String, String> configEntry : configs.entrySet()) {
                    if ("canary".equals(configEntry.getKey())) {
                        continue; // canary 已单独处理
                    }
                    try {
                        GovernancePolicy policy = governanceStorage.safeDeserialize(configEntry.getValue());
                        if (mergedPatch == null) {
                            mergedPatch = policy;
                        } else {
                            mergedPatch = GovernancePolicy.merge(mergedPatch, policy);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to restore governance configuration, skipped: lingId={}, type={}", lingId, configEntry.getKey(), e);
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
