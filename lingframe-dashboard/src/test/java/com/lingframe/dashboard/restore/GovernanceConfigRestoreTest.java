package com.lingframe.dashboard.restore;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.dashboard.storage.GovernanceConfigRestorer;
import com.lingframe.dashboard.storage.GovernanceStorage;
import com.lingframe.dashboard.storage.StorageInitializer;
import com.lingframe.dashboard.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 治理配置恢复集成测试
 */
class GovernanceConfigRestoreTest {

    private JdbcTemplate jdbcTemplate;
    private StorageInitializer initializer;
    private GovernanceStorage governanceStorage;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        jdbcTemplate = new JdbcTemplate(ds);
        initializer = new StorageInitializer(jdbcTemplate, new StorageProperties());
        initializer.init();
        governanceStorage = new GovernanceStorage(jdbcTemplate, objectMapper);
    }

    @Test
    void restoreGovernanceConfig_success() throws Exception {
        // 准备：写入治理配置
        GovernancePolicy policy = GovernancePolicy.builder().build();
        policy.setPermissions(new ArrayList<>());
        policy.setCapabilities(new ArrayList<>());
        policy.setAudits(new ArrayList<>());
        String json = objectMapper.writeValueAsString(policy);
        governanceStorage.saveInvocationConfig("ling-restore", json);

        // 执行恢复
        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);
        when(mockRegistry.getPatchForUpdate(anyString())).thenReturn(null);

        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(governanceStorage, mockRegistry, null, objectMapper);
        restorer.restore();

        // 验证：updatePatch 被调用
        verify(mockRegistry).persistPolicyPatch(eq("ling-restore"), any(GovernancePolicy.class));
    }

    @Test
    void restoreCanaryConfig_success() throws Exception {
        // 准备：写入灰度配置
        governanceStorage.saveMigrationConfig("ling-canary", "{\"percent\":50,\"canaryVersion\":\"v2\"}");

        // 执行恢复
        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);

        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(governanceStorage, mockRegistry, null, objectMapper);
        restorer.restore();
    }

    @Test
    void restoreEmptyConfig_noError() {
        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);
        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(governanceStorage, mockRegistry, null, objectMapper);
        // 无数据时不应抛异常
        assertDoesNotThrow(restorer::restore);
        verify(mockRegistry, never()).persistPolicyPatch(anyString(), any(GovernancePolicy.class));
    }

    @Test
    void safeDeserialize_nullCollections_getDefaults() {
        // 模拟缺少集合字段的 JSON
        String json = "{}";
        GovernancePolicy policy = governanceStorage.safeDeserialize(json);

        assertNotNull(policy.getPermissions());
        assertNotNull(policy.getCapabilities());
        assertNotNull(policy.getAudits());
        assertNotNull(policy.getInvocation());
    }
}
