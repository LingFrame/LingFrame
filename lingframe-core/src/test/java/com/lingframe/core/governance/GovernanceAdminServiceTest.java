package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.InvocationConfigDTO;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.InstancePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GovernanceAdminService} 关键语义单测。
 */
@DisplayName("GovernanceAdminService 治理管理服务单测")
class GovernanceAdminServiceTest {

    @DisplayName("策略合并与补丁管理语义")
    @Nested
    class PolicyMergeSemantics {

        @DisplayName("静态策略与补丁均为空时，生效策略为 null")
        @Test
        void getEffectivePolicy_returnsNull_whenBothNull(@TempDir Path tempDir) {
            GovernanceAdminService svc = newServiceWithoutStaticPolicy(tempDir);

            assertNull(svc.getEffectivePolicy("empty-ling"));
        }

        @DisplayName("仅静态策略存在时，生效策略返回静态策略拷贝")
        @Test
        void getEffectivePolicy_returnsStaticOnly(@TempDir Path tempDir) {
            GovernancePolicy staticPolicy = policyWith("storage:sql", AccessType.READ);
            GovernanceAdminService svc = newServiceWithStaticPolicy(tempDir, staticPolicy);

            GovernancePolicy effective = svc.getEffectivePolicy("static-only");

            assertNotNull(effective);
            assertEquals(1, effective.getCapabilities().size());
            // 返回的是拷贝，不是真源
            assertFalse(staticPolicy.getCapabilities() == effective.getCapabilities());
        }

        @DisplayName("静态策略与补丁合并后，生效策略包含两者")
        @Test
        void getEffectivePolicy_mergesStaticAndPatch(@TempDir Path tempDir) {
            GovernancePolicy staticPolicy = policyWith("storage:sql", AccessType.READ);
            GovernanceAdminService svc = newServiceWithStaticPolicy(tempDir, staticPolicy);

            GovernancePolicy patch = policyWith("cache:local", AccessType.WRITE);
            svc.persistPolicyPatch("merged-ling", patch);

            GovernancePolicy effective = svc.getEffectivePolicy("merged-ling");

            assertNotNull(effective);
            // merge 后 patch 覆盖 static 的 capabilities 列表
            assertEquals(1, effective.getCapabilities().size());
            assertEquals("cache:local", effective.getCapabilities().get(0).getCapability());
        }
    }

    @DisplayName("补丁拷贝语义")
    @Nested
    class PatchCopySemantics {

        @DisplayName("无补丁时返回空策略对象，可安全修改")
        @Test
        void getPatchForUpdate_returnsEmpty_whenNoPatch(@TempDir Path tempDir) {
            GovernanceAdminService svc = newServiceWithoutStaticPolicy(tempDir);

            GovernancePolicy patch = svc.getPatchForUpdate("no-patch");

            assertNotNull(patch);
            assertTrue(patch.getCapabilities().isEmpty());
        }

        @DisplayName("有补丁时返回拷贝，修改不影响注册表真源")
        @Test
        void getPatchForUpdate_returnsCopy(@TempDir Path tempDir) {
            GovernanceAdminService svc = newServiceWithoutStaticPolicy(tempDir);
            GovernancePolicy original = policyWith("storage:sql", AccessType.READ);
            svc.persistPolicyPatch("copy-test", original);

            GovernancePolicy patch = svc.getPatchForUpdate("copy-test");
            patch.getCapabilities().add(
                    GovernancePolicy.CapabilityRule.builder()
                            .capability("cache:local")
                            .accessType(AccessType.WRITE.name())
                            .build());

            // 真源不应被拷贝修改影响
            GovernancePolicy effective = svc.getEffectivePolicy("copy-test");
            assertEquals(1, effective.getCapabilities().size());
            assertEquals("storage:sql", effective.getCapabilities().get(0).getCapability());
        }
    }

    @DisplayName("补丁持久化与权限同步语义")
    @Nested
    class PersistAndSyncSemantics {

        @DisplayName("persistPolicyPatch 落库并同步权限表")
        @Test
        void persistPolicyPatch_syncsPermissions(@TempDir Path tempDir) {
            PermissionService permissionService = mock(PermissionService.class);
            LocalGovernanceRegistry registry = new LocalGovernanceRegistry(null,
                    tempDir.resolve("patch.yml").toString());
            GovernanceAdminService svc = new GovernanceAdminService(null, registry, permissionService);

            GovernancePolicy patch = policyWith("storage:sql", AccessType.WRITE);
            svc.persistPolicyPatch("sync-test", patch);

            // 权限同步器应通过 replacePermissions 原子替换权限，而非逐条 grant
            verify(permissionService, atLeast(1)).replacePermissions(eq("sync-test"), argThat(map ->
                    map != null && map.size() == 1
                            && AccessType.WRITE.equals(map.get("storage:sql"))));
        }

        @DisplayName("lingId 为空时跳过持久化，不抛异常")
        @Test
        void persistPolicyPatch_skipsBlankLingId(@TempDir Path tempDir) {
            GovernanceAdminService svc = newServiceWithoutStaticPolicy(tempDir);

            svc.persistPolicyPatch("", policyWith("storage:sql", AccessType.READ));

            // 无副作用即可，不抛异常是关键语义
            assertNull(svc.getEffectivePolicy(""));
        }

        @DisplayName("补丁能力规则为空时，原子替换为空权限集而非 removeLing 造成真空")
        @Test
        void persistPolicyPatch_clearsPermissions_whenEmptyCapabilities(@TempDir Path tempDir) {
            PermissionService permissionService = mock(PermissionService.class);
            LocalGovernanceRegistry registry = new LocalGovernanceRegistry(null,
                    tempDir.resolve("patch.yml").toString());
            GovernanceAdminService svc = new GovernanceAdminService(null, registry, permissionService);

            svc.persistPolicyPatch("clear-test", new GovernancePolicy());

            // replacePermissions 应被调，传入空 map 等价于清空（原子替换，非 removeLing 造成权限真空）
            verify(permissionService, atLeast(1)).replacePermissions(eq("clear-test"), argThat(map ->
                    map != null && map.isEmpty()));
        }
    }

    // ==================== 辅助构造 ====================

    private GovernanceAdminService newServiceWithoutStaticPolicy(Path tempDir) {
        LocalGovernanceRegistry registry = new LocalGovernanceRegistry(null,
                tempDir.resolve("patch.yml").toString());
        return new GovernanceAdminService(null, registry, mock(PermissionService.class));
    }

    private GovernanceAdminService newServiceWithStaticPolicy(Path tempDir, GovernancePolicy staticPolicy) {
        LingRepository repository = mock(LingRepository.class);
        LingRuntime runtime = mock(LingRuntime.class);
        LingInstance instance = mock(LingInstance.class);
        LingDefinition definition = mock(LingDefinition.class);

        when(repository.getRuntime(any())).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(mock(InstancePool.class));
        when(runtime.getInstancePool().getDefault()).thenReturn(instance);
        when(instance.getDefinition()).thenReturn(definition);
        when(definition.getGovernance()).thenReturn(staticPolicy);

        LocalGovernanceRegistry registry = new LocalGovernanceRegistry(null,
                tempDir.resolve("patch.yml").toString());
        return new GovernanceAdminService(repository, registry, mock(PermissionService.class));
    }

    @DisplayName("调用治理配置统一下发语义")
    @Nested
    class UpdateInvocationConfigSemantics {

        @DisplayName("下发后只写 patch 真源，不同步 LingRuntimeConfig，不驱逐缓存")
        @Test
        void updateInvocationConfig_writesPatchOnly(@TempDir Path tempDir) {
            // 治本后：治理策略唯一真源是 patch registry，预填充 filter 在 RESILIENCE 之前把
            // effective policy 预填到 ctx.governance()，弹性组件通过 ctx 读取，无需同步底座也无需驱逐缓存
            LingRepository repository = mock(LingRepository.class);
            LingRuntime runtime = mock(LingRuntime.class);
            LingInstance instance = mock(LingInstance.class);
            LingDefinition definition = mock(LingDefinition.class);
            when(repository.getRuntime(any())).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(mock(InstancePool.class));
            when(runtime.getInstancePool().getDefault()).thenReturn(instance);
            when(instance.getDefinition()).thenReturn(definition);
            when(definition.getGovernance()).thenReturn(new GovernancePolicy());
            LocalGovernanceRegistry registry = new LocalGovernanceRegistry(null,
                    tempDir.resolve("patch.yml").toString());
            GovernanceAdminService svc = new GovernanceAdminService(
                    repository, registry, mock(PermissionService.class));

            InvocationConfigDTO config = InvocationConfigDTO.builder()
                    .timeoutMs(5000)
                    .rateLimitPerSecond(100)
                    .maxConcurrentThreads(20)
                    .build();

            svc.updateInvocationConfig("order-ling", config);

            // 验证 GovernancePolicy.InvocationPolicy 已写入（治理策略唯一真源）
            GovernancePolicy effective = svc.getEffectivePolicy("order-ling");
            assertNotNull(effective.getInvocation());
            assertEquals(5000, effective.getInvocation().getTimeoutMs());
            assertEquals(100, effective.getInvocation().getRateLimitPerSecond());
            assertEquals(20, effective.getInvocation().getMaxConcurrentThreads());

            // 治本后不再同步 LingRuntimeConfig 底座（双写已消除）
            verify(runtime, never()).updateConfig(any());
        }

        @DisplayName("null 字段表示不动，不覆盖现有值")
        @Test
        void nullFields_keepExisting(@TempDir Path tempDir) {
            GovernancePolicy existing = new GovernancePolicy();
            existing.setInvocation(GovernancePolicy.InvocationPolicy.builder()
                    .timeoutMs(3000)
                    .retryCount(3)
                    .build());
            GovernanceAdminService svc = newServiceWithStaticPolicy(tempDir, existing);

            // 只下发 rateLimitPerSecond，其他字段为 null
            InvocationConfigDTO config = InvocationConfigDTO.builder()
                    .rateLimitPerSecond(50)
                    .build();

            svc.updateInvocationConfig("order-ling", config);

            GovernancePolicy effective = svc.getEffectivePolicy("order-ling");
            // timeoutMs 和 retryCount 应保留原值，不被 null 覆盖
            assertEquals(3000, effective.getInvocation().getTimeoutMs());
            assertEquals(3, effective.getInvocation().getRetryCount());
            assertEquals(50, effective.getInvocation().getRateLimitPerSecond());
        }

        @DisplayName("灵元未装载时不下发，不抛异常")
        @Test
        void skipsWhenLingNotLoaded(@TempDir Path tempDir) {
            LingRepository repository = mock(LingRepository.class);
            when(repository.getRuntime(any())).thenReturn(null);
            GovernanceAdminService svc = new GovernanceAdminService(
                    repository, null, mock(PermissionService.class));

            // 不抛异常是关键语义
            svc.updateInvocationConfig("unknown-ling",
                    InvocationConfigDTO.builder().timeoutMs(1000).build());
        }

        @DisplayName("lingId 为空时跳过，不抛异常")
        @Test
        void skipsBlankLingId(@TempDir Path tempDir) {
            GovernanceAdminService svc = newServiceWithStaticPolicy(tempDir, new GovernancePolicy());

            svc.updateInvocationConfig("", InvocationConfigDTO.builder().timeoutMs(1000).build());

            // 无副作用即可，不抛异常是关键语义
        }

        @DisplayName("config 为 null 时抛 IllegalArgumentException")
        @Test
        void throwsWhenConfigNull(@TempDir Path tempDir) {
            GovernanceAdminService svc = newServiceWithStaticPolicy(tempDir, new GovernancePolicy());

            assertThrows(IllegalArgumentException.class,
                    () -> svc.updateInvocationConfig("order-ling", null));
        }
    }

    private GovernancePolicy policyWith(String capability, AccessType accessType) {
        GovernancePolicy policy = new GovernancePolicy();
        List<GovernancePolicy.CapabilityRule> capabilities = new ArrayList<>();
        capabilities.add(GovernancePolicy.CapabilityRule.builder()
                .capability(capability)
                .accessType(accessType.name())
                .build());
        policy.setCapabilities(capabilities);
        return policy;
    }
}
