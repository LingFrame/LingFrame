package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.router.CanaryRouter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import java.util.Map;

/**
 * 启动时从 SQLite 恢复治理配置到 LocalGovernanceRegistry 和 CanaryRouter
 */
@Slf4j
public class GovernanceConfigRestorer {

    private final GovernanceStorage governanceStorage;
    private final LocalGovernanceRegistry governanceRegistry;
    private final CanaryRouter canaryRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GovernanceConfigRestorer(GovernanceStorage governanceStorage,
                                    LocalGovernanceRegistry governanceRegistry,
                                    CanaryRouter canaryRouter) {
        this.governanceStorage = governanceStorage;
        this.governanceRegistry = governanceRegistry;
        this.canaryRouter = canaryRouter;
    }

    @PostConstruct
    public void restore() {
        try {
            Map<String, Map<String, String>> allConfigs = governanceStorage.loadAllConfigs();
            if (allConfigs.isEmpty()) {
                log.info("无持久化治理配置需要恢复");
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
                        log.warn("恢复灰度配置失败: lingId={}", lingId, e);
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
                        log.warn("恢复治理配置失败，跳过: lingId={}, type={}", lingId, configEntry.getKey(), e);
                    }
                }

                if (mergedPatch != null) {
                    governanceRegistry.updatePatch(lingId, mergedPatch);
                    hasPatch = true;
                }

                if (hasPatch) {
                    restored++;
                }
            }
            log.info("恢复治理配置完成: {} 个灵元", restored);
        } catch (Exception e) {
            log.warn("恢复治理配置失败（不影响启动）", e);
        }
    }
}
