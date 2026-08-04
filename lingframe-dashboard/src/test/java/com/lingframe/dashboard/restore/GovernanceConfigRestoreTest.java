package com.lingframe.dashboard.restore;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.routing.MigrationPhase;
import com.lingframe.core.routing.MigrationStateHolder;
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
                governanceStorage, mockRegistry, null, holder, objectMapper);
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
                governanceStorage, mockRegistry, null, holder, objectMapper);
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
                governanceStorage, mockRegistry, null, null, objectMapper);
        assertDoesNotThrow(restorer::restore);
    }

    @Test
    @DisplayName("旧 percent 格式应推导重建阶段（50→MIGRATING，100→LING_EXCLUSIVE）")
    void restoreLegacyPercent_derivesPhase() {
        governanceStorage.saveMigrationConfig("svc-50", "{\"percent\":50,\"contractId\":\"svc-50\",\"oldCandidate\":\"lingcore-app\",\"newCandidate\":\"user-ling\"}");
        governanceStorage.saveMigrationConfig("svc-100", "{\"percent\":100,\"contractId\":\"svc-100\",\"oldCandidate\":\"lingcore-app\",\"newCandidate\":\"user-ling\"}");
        governanceStorage.saveMigrationConfig("svc-0", "{\"percent\":0,\"contractId\":\"svc-0\",\"oldCandidate\":\"lingcore-app\",\"newCandidate\":\"user-ling\"}");

        MigrationStateHolder holder = new MigrationStateHolder();
        GovernanceAdminService mockRegistry = mock(GovernanceAdminService.class);
        GovernanceConfigRestorer restorer = new GovernanceConfigRestorer(
                governanceStorage, mockRegistry, null, holder, objectMapper);
        restorer.restore();

        assertEquals(MigrationPhase.MIGRATING, holder.getPhase("svc-50"));
        assertEquals(MigrationPhase.LING_EXCLUSIVE, holder.getPhase("svc-100"));
        // percent=0 表示回退灵核，保持默认 CORE_EXCLUSIVE 不重建
        assertEquals(MigrationPhase.CORE_EXCLUSIVE, holder.getPhase("svc-0"));
        // 独占态记录的候选归属：percent=100 流量全在灵元侧，保留方应为进入方（newCandidate）
        MigrationStateHolder.PhaseRecord rec = holder.getRecord("svc-100");
        assertNotNull(rec);
        assertEquals("user-ling", rec.getOldCandidate(),
                "LING_EXCLUSIVE 独占态 oldCandidate 应为保留方（灵元），不能误落成灵核");
        assertNull(rec.getNewCandidate(), "独占态 newCandidate 应为 null");
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
