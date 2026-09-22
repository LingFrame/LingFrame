package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GovernanceStorage 补充测试
 * <p>
 * StorageIntegrationTest 仅覆盖 canary 配置的 upsert/loadAllConfigs。
 * 本类补齐 invocation/permission 配置、灵元状态持久化、safeDeserialize 的 null 集合兜底与异常分支。
 */
@DisplayName("GovernanceStorage 补充测试")
class GovernanceStorageSupplementTest {

    private JdbcTemplate jdbcTemplate;
    private GovernanceStorage storage;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        jdbcTemplate = new JdbcTemplate(ds);
        StorageInitializer initializer = new StorageInitializer(jdbcTemplate, new StorageProperties());
        initializer.init();
        storage = new GovernanceStorage(jdbcTemplate, new ObjectMapper());
    }

    // ==================== 调用治理配置 ====================

    @Nested
    @DisplayName("调用治理配置")
    class InvocationConfigTests {

        @Test
        @DisplayName("saveInvocationConfig + loadInvocationConfig 应正确读写")
        void shouldSaveAndLoadInvocationConfig() {
            String json = "{\"timeoutMs\":1000,\"rateLimitPerSecond\":10}";
            storage.saveInvocationConfig("ling1", json);

            String loaded = storage.loadInvocationConfig("ling1");

            assertEquals(json, loaded);
        }

        @Test
        @DisplayName("loadInvocationConfig 不存在时应返回 null")
        void shouldReturnNullWhenInvocationConfigMissing() {
            assertNull(storage.loadInvocationConfig("non-existent"));
        }

        @Test
        @DisplayName("upsert 应更新已有调用治理配置")
        void shouldUpdateExistingInvocationConfig() {
            storage.saveInvocationConfig("ling1", "{\"timeoutMs\":1000}");
            storage.saveInvocationConfig("ling1", "{\"timeoutMs\":2000}");

            String loaded = storage.loadInvocationConfig("ling1");

            assertTrue(loaded.contains("2000"));
            assertFalse(loaded.contains("1000"));
        }
    }

    // ==================== 权限配置 ====================

    @Nested
    @DisplayName("权限配置")
    class PermissionConfigTests {

        @Test
        @DisplayName("savePermissionConfig + loadPermissionConfig 应正确读写")
        void shouldSaveAndLoadPermissionConfig() {
            String json = "{\"dbRead\":true,\"dbWrite\":false}";
            storage.savePermissionConfig("ling1", json);

            String loaded = storage.loadPermissionConfig("ling1");

            assertEquals(json, loaded);
        }

        @Test
        @DisplayName("loadPermissionConfig 不存在时应返回 null")
        void shouldReturnNullWhenPermissionConfigMissing() {
            assertNull(storage.loadPermissionConfig("non-existent"));
        }
    }

    // ==================== 灵元状态 ====================

    @Nested
    @DisplayName("灵元状态")
    class LingStatusTests {

        @Test
        @DisplayName("saveLingStatus 新增后应能被 loadAllLingStatuses 读取")
        void shouldSaveAndLoadLingStatus() {
            storage.saveLingStatus("ling1", "ACTIVE", "1.0.0");

            Map<String, Map<String, String>> statuses = storage.loadAllLingStatuses();

            assertTrue(statuses.containsKey("ling1"));
            assertEquals("ACTIVE", statuses.get("ling1").get("status"));
            assertEquals("1.0.0", statuses.get("ling1").get("version"));
        }

        @Test
        @DisplayName("saveLingStatus 应更新已有状态（upsert）")
        void shouldUpdateExistingLingStatus() {
            storage.saveLingStatus("ling1", "ACTIVE", "1.0.0");
            storage.saveLingStatus("ling1", "INACTIVE", "1.1.0");

            Map<String, Map<String, String>> statuses = storage.loadAllLingStatuses();

            assertEquals("INACTIVE", statuses.get("ling1").get("status"));
            assertEquals("1.1.0", statuses.get("ling1").get("version"));
        }

        @Test
        @DisplayName("无状态记录时 loadAllLingStatuses 应返回空 Map")
        void shouldReturnEmptyMapWhenNoStatus() {
            Map<String, Map<String, String>> statuses = storage.loadAllLingStatuses();

            assertTrue(statuses.isEmpty());
        }

        @Test
        @DisplayName("应支持多个灵元的状态记录")
        void shouldSupportMultipleLingStatuses() {
            storage.saveLingStatus("ling1", "ACTIVE", "1.0.0");
            storage.saveLingStatus("ling2", "DEGRADED", "2.0.0");

            Map<String, Map<String, String>> statuses = storage.loadAllLingStatuses();

            assertEquals(2, statuses.size());
            assertEquals("ACTIVE", statuses.get("ling1").get("status"));
            assertEquals("DEGRADED", statuses.get("ling2").get("status"));
        }
    }

    // ==================== loadAllConfigs ====================

    @Nested
    @DisplayName("loadAllConfigs")
    class LoadAllConfigsTests {

        @Test
        @DisplayName("应按 lingId 分组返回所有配置类型")
        void shouldGroupByLingId() {
            storage.saveMigrationConfig("ling1", "{\"p\":1}");
            storage.saveInvocationConfig("ling1", "{\"t\":2}");
            storage.savePermissionConfig("ling2", "{\"r\":3}");

            Map<String, Map<String, String>> all = storage.loadAllConfigs();

            assertEquals(2, all.size());
            assertEquals(2, all.get("ling1").size());
            assertTrue(all.get("ling1").containsKey("migration"));
            assertTrue(all.get("ling1").containsKey("invocation"));
            assertEquals(1, all.get("ling2").size());
            assertTrue(all.get("ling2").containsKey("permission"));
        }

        @Test
        @DisplayName("无配置时应返回空 Map")
        void shouldReturnEmptyWhenNoConfig() {
            Map<String, Map<String, String>> all = storage.loadAllConfigs();

            assertTrue(all.isEmpty());
        }
    }

    // ==================== safeDeserialize ====================

    @Nested
    @DisplayName("safeDeserialize")
    class SafeDeserializeTests {

        @Test
        @DisplayName("完整 JSON 应正确反序列化")
        void shouldDeserializeCompleteJson() {
            String json = "{\"capabilities\":[],\"permissions\":[],\"audits\":[],\"invocation\":{}}";

            GovernancePolicy policy = storage.safeDeserialize(json);

            assertNotNull(policy);
            assertNotNull(policy.getCapabilities());
            assertNotNull(policy.getPermissions());
            assertNotNull(policy.getAudits());
            assertNotNull(policy.getInvocation());
        }

        @Test
        @DisplayName("缺失 capabilities 时应兜底为空列表")
        void shouldFallbackToEmptyListWhenCapabilitiesMissing() {
            String json = "{}";

            GovernancePolicy policy = storage.safeDeserialize(json);

            assertNotNull(policy.getCapabilities());
            assertTrue(policy.getCapabilities().isEmpty());
        }

        @Test
        @DisplayName("缺失 permissions 时应兜底为空列表")
        void shouldFallbackToEmptyListWhenPermissionsMissing() {
            String json = "{}";

            GovernancePolicy policy = storage.safeDeserialize(json);

            assertNotNull(policy.getPermissions());
            assertTrue(policy.getPermissions().isEmpty());
        }

        @Test
        @DisplayName("缺失 audits 时应兜底为空列表")
        void shouldFallbackToEmptyListWhenAuditsMissing() {
            String json = "{}";

            GovernancePolicy policy = storage.safeDeserialize(json);

            assertNotNull(policy.getAudits());
            assertTrue(policy.getAudits().isEmpty());
        }

        @Test
        @DisplayName("缺失 invocation 时应兜底为空对象")
        void shouldFallbackToEmptyObjectWhenInvocationMissing() {
            String json = "{}";

            GovernancePolicy policy = storage.safeDeserialize(json);

            assertNotNull(policy.getInvocation());
        }

        @Test
        @DisplayName("非法 JSON 应抛 RuntimeException")
        void shouldThrowOnInvalidJson() {
            assertThrows(RuntimeException.class,
                    () -> storage.safeDeserialize("not-a-valid-json"));
        }

        @Test
        @DisplayName("null JSON 应抛 RuntimeException")
        void shouldThrowOnNullJson() {
            assertThrows(RuntimeException.class,
                    () -> storage.safeDeserialize(null));
        }
    }
}
