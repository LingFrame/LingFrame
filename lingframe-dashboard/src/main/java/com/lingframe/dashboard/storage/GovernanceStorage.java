package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 治理配置存储：灰度/调用治理/权限配置的持久化与恢复
 */
@Slf4j
@RequiredArgsConstructor
public class GovernanceStorage {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // ==================== 灰度配置 ====================

    public void saveCanaryConfig(String lingId, String configJson) {
        saveConfig(lingId, "canary", configJson);
    }

    public String loadCanaryConfig(String lingId) {
        return loadConfig(lingId, "canary");
    }

    // ==================== 调用治理配置 ====================

    public void saveInvocationConfig(String lingId, String configJson) {
        saveConfig(lingId, "invocation", configJson);
    }

    public String loadInvocationConfig(String lingId) {
        return loadConfig(lingId, "invocation");
    }

    // ==================== 权限配置 ====================

    public void savePermissionConfig(String lingId, String configJson) {
        saveConfig(lingId, "permission", configJson);
    }

    public String loadPermissionConfig(String lingId) {
        return loadConfig(lingId, "permission");
    }

    // ==================== 通用读写 ====================

    private void saveConfig(String lingId, String configType, String configJson) {
        long now = System.currentTimeMillis();
        int updated = jdbcTemplate.update(
            "UPDATE governance_config SET config_data = ?, updated_at = ? " +
            "WHERE ling_id = ? AND config_type = ?",
            configJson, now, lingId, configType
        );
        if (updated == 0) {
            jdbcTemplate.update(
                "INSERT INTO governance_config (ling_id, config_type, config_data, updated_at) VALUES (?, ?, ?, ?)",
                lingId, configType, configJson, now
            );
        }
    }

    private String loadConfig(String lingId, String configType) {
        List<String> results = jdbcTemplate.queryForList(
            "SELECT config_data FROM governance_config WHERE ling_id = ? AND config_type = ?",
            String.class, lingId, configType
        );
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 加载所有治理配置
     */
    public Map<String, Map<String, String>> loadAllConfigs() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT ling_id, config_type, config_data FROM governance_config"
        );
        Map<String, Map<String, String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String lingId = (String) row.get("ling_id");
            String configType = (String) row.get("config_type");
            String configData = (String) row.get("config_data");
            result.computeIfAbsent(lingId, k -> new HashMap<>()).put(configType, configData);
        }
        return result;
    }

    // ==================== 灵元状态 ====================

    public void saveLingStatus(String lingId, String status, String version) {
        long now = System.currentTimeMillis();
        int updated = jdbcTemplate.update(
            "UPDATE ling_status SET status = ?, version = ?, updated_at = ? WHERE ling_id = ?",
            status, version, now, lingId
        );
        if (updated == 0) {
            jdbcTemplate.update(
                "INSERT INTO ling_status (ling_id, status, version, updated_at) VALUES (?, ?, ?, ?)",
                lingId, status, version, now
            );
        }
    }

    public Map<String, Map<String, String>> loadAllLingStatuses() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT ling_id, status, version FROM ling_status"
        );
        Map<String, Map<String, String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, String> status = new HashMap<>();
            status.put("status", (String) row.get("status"));
            status.put("version", (String) row.get("version"));
            result.put((String) row.get("ling_id"), status);
        }
        return result;
    }

    // ==================== 反序列化 ====================

    /**
     * 安全反序列化 GovernancePolicy JSON。
     * Jackson 不走 @Builder.Default，需手动兜底 null 集合。
     */
    public GovernancePolicy safeDeserialize(String json) {
        try {
            GovernancePolicy policy = objectMapper.readValue(json, GovernancePolicy.class);
            if (policy.getPermissions() == null) {
                policy.setPermissions(new ArrayList<>());
            }
            if (policy.getCapabilities() == null) {
                policy.setCapabilities(new ArrayList<>());
            }
            if (policy.getAudits() == null) {
                policy.setAudits(new ArrayList<>());
            }
            if (policy.getInvocation() == null) {
                policy.setInvocation(new GovernancePolicy.InvocationPolicy());
            }
            return policy;
        } catch (Exception e) {
            throw new RuntimeException("反序列化 GovernancePolicy 失败: " + json, e);
        }
    }
}
