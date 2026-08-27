package com.lingframe.dashboard.restore;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.routing.MigrationPhase;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.dashboard.storage.GovernanceConfigRestorer;
import com.lingframe.dashboard.storage.GovernanceStorage;
import com.lingframe.dashboard.storage.StorageInitializer;
import com.lingframe.dashboard.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 治理配置恢复集成测试
 */
@DisplayName("治理配置恢复集成测试")
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
    @DisplayName("恢复治理配置应落库到治理注册表")
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

        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(governanceStorage, mockRegistry, objectMapper);
        restorer.restore();

        // 验证：updatePatch 被调用
        verify(mockRegistry).persistPolicyPatch(eq("ling-restore"), any(GovernancePolicy.class));
    }

    @Test
    @DisplayName("无持久化配置时恢复不应抛异常")
    void restoreEmptyConfig_noError() {
        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);
        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(governanceStorage, mockRegistry, objectMapper);
        // 无数据时不应抛异常
        assertDoesNotThrow(restorer::restore);
        verify(mockRegistry, never()).persistPolicyPatch(anyString(), any(GovernancePolicy.class));
    }

    @Test
    @DisplayName("新格式迁移阶段记录应重建 MigrationStateHolder 阶段与候选元数据")
    void restoreMigrationPhase_rebuildsHolderPhase() throws Exception {
        // 准备：按 MigrationStateHolder 相变落盘的新格式写入
        governanceStorage.save("svc", new MigrationStateHolder.PhaseRecord(
                MigrationPhase.MIGRATING, "lingcore-app", "user-ling"));
        governanceStorage.save("svc-done", new MigrationStateHolder.PhaseRecord(
                MigrationPhase.LING_EXCLUSIVE, "user-ling", null));

        MigrationStateHolder holder = new MigrationStateHolder();
        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);
        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(
                governanceStorage, mockRegistry, holder, objectMapper);
        restorer.restore();

        // 验证：阶段与候选元数据完整恢复
        assertEquals(MigrationPhase.MIGRATING, holder.getPhase("svc"));
        assertEquals("lingcore-app", holder.getRecord("svc").getOldCandidate());
        assertEquals("user-ling", holder.getRecord("svc").getNewCandidate());
        assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc-done"));
        assertEquals("user-ling", holder.getRecord("svc-done").getOldCandidate());
    }

    @Test
    @DisplayName("恢复后的 MIGRATING 阶段可继续推进相变（重启往返一致性）")
    void restoredPhaseAllowsContinuedTransition() throws Exception {
        governanceStorage.save("svc", new MigrationStateHolder.PhaseRecord(
                MigrationPhase.MIGRATING, "lingcore-app", "user-ling"));

        MigrationStateHolder holder = new MigrationStateHolder();
        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);
        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(
                governanceStorage, mockRegistry, holder, objectMapper);
        restorer.restore();

        // 重启后运维确认迁移：权重排空后确认相变
        holder.confirmPhaseTransition("svc", true);
        assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc"));
    }

    @Test
    @DisplayName("holder 为 null 时恢复阶段应安全降级不抛异常")
    void restorePhaseWithoutHolder_skipsGracefully() {
        governanceStorage.saveMigrationConfig("svc", "{\"phase\":\"MIGRATING\",\"oldCandidate\":\"lingcore-app\",\"newCandidate\":\"user-ling\"}");

        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);
        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(
                governanceStorage, mockRegistry, null, objectMapper);
        assertDoesNotThrow(restorer::restore);
    }

    @Test
    @DisplayName("反序列化缺集合字段 JSON 应返回默认集合")
    void safeDeserialize_nullCollections_getDefaults() {
        // 模拟缺少集合字段的 JSON
        String json = "{}";
        GovernancePolicy policy = governanceStorage.safeDeserialize(json);

        assertNotNull(policy.getPermissions());
        assertNotNull(policy.getCapabilities());
        assertNotNull(policy.getAudits());
        assertNotNull(policy.getInvocation());
    }

    @Test
    @DisplayName("恢复契约路由权重应写入 ProviderWeightRouter")
    void restoreRoutingWeights_success() throws Exception {
        String contractId = "com.example.UserService";
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("lingcore-app", 1);
        weights.put("user-ling:1.0.0", 2);
        weights.put("user-ling:2.0.0", 1);

        // 准备：写入结构化路由权重配置
        governanceStorage.saveRoutingWeightConfig(contractId, objectMapper.writeValueAsString(weights));

        ProviderWeightRouter router = new ProviderWeightRouter();
        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);
        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(
                governanceStorage, mockRegistry, null, router, objectMapper);
        restorer.restore();

        // 验证：权重成功恢复到 ProviderWeightRouter
        assertEquals(1, router.getOverrideWeight(contractId, "lingcore-app"));
        assertEquals(2, router.getOverrideWeight(contractId, "user-ling:1.0.0"));
        assertEquals(1, router.getOverrideWeight(contractId, "user-ling:2.0.0"));
    }
}