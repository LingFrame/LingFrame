package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DisplayName("GovernancePermissionSynchronizer 测试")
class GovernancePermissionSynchronizerTest {

    @Test
    @DisplayName("syncPolicy 应使用持久化能力规则原子替换运行时权限")
    void syncPolicyShouldReplaceRuntimePermissionsWithPersistedCapabilities() {
        PermissionService permissionService = mock(PermissionService.class);
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Arrays.asList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.STORAGE_SQL)
                                .accessType("WRITE")
                                .build(),
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.CACHE_LOCAL)
                                .accessType("READ")
                                .build()))
                .build();

        int synced = GovernancePermissionSynchronizer.syncPolicy("demo-ling", policy, permissionService);

        assertEquals(2, synced);
        // 验证通过 replacePermissions 原子替换，而非逐条 grant
        verify(permissionService).replacePermissions(eq("demo-ling"), argThat(map ->
                map != null && map.size() == 2
                        && AccessType.WRITE.equals(map.get(Capabilities.STORAGE_SQL))
                        && AccessType.READ.equals(map.get(Capabilities.CACHE_LOCAL))));
        verifyNoMoreInteractions(permissionService);
    }

    @Test
    @DisplayName("syncPolicy 在持久化策略为空时应清空运行时权限")
    void syncPolicyShouldClearRuntimePermissionsWhenPersistedPolicyIsEmpty() {
        PermissionService permissionService = mock(PermissionService.class);

        int synced = GovernancePermissionSynchronizer.syncPolicy("demo-ling", new GovernancePolicy(), permissionService);

        assertEquals(0, synced);
        // 验证通过 replacePermissions 传入空 map 等价于清空，而非调用 removeLing 造成权限真空
        verify(permissionService).replacePermissions(eq("demo-ling"), argThat(map ->
                map != null && map.isEmpty()));
        verifyNoMoreInteractions(permissionService);
    }

    @Test
    @DisplayName("syncPolicy 应忽略格式异常的能力规则")
    void syncPolicyShouldIgnoreMalformedCapabilityRules() {
        PermissionService permissionService = mock(PermissionService.class);
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Arrays.asList(
                        null,
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(" ")
                                .accessType("WRITE")
                                .build(),
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.STORAGE_SQL)
                                .accessType("INVALID")
                                .build(),
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.CACHE_LOCAL)
                                .accessType("write")
                                .build()))
                .build();

        int synced = GovernancePermissionSynchronizer.syncPolicy("demo-ling", policy, permissionService);

        assertEquals(1, synced);
        // 验证只保留有效规则，通过 replacePermissions 原子替换
        verify(permissionService).replacePermissions(eq("demo-ling"), argThat(map ->
                map != null && map.size() == 1
                        && AccessType.WRITE.equals(map.get(Capabilities.CACHE_LOCAL))));
        verifyNoMoreInteractions(permissionService);
    }

    @Test
    @DisplayName("syncAll 应恢复所有持久化 patch")
    void syncAllShouldRestoreEveryPersistedPatch() {
        PermissionService permissionService = mock(PermissionService.class);
        Map<String, GovernancePolicy> patches = new LinkedHashMap<>();
        patches.put("ling-a", GovernancePolicy.builder()
                .capabilities(Collections.singletonList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.STORAGE_SQL)
                                .accessType("READ")
                                .build()))
                .build());
        patches.put("ling-b", GovernancePolicy.builder()
                .capabilities(Collections.singletonList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.CACHE_LOCAL)
                                .accessType("NONE")
                                .build()))
                .build());

        int synced = GovernancePermissionSynchronizer.syncAll(patches, permissionService);

        assertEquals(2, synced);
        // 验证每个 ling 都通过 replacePermissions 原子替换
        verify(permissionService).replacePermissions(eq("ling-a"), argThat(map ->
                map != null && map.size() == 1
                        && AccessType.READ.equals(map.get(Capabilities.STORAGE_SQL))));
        verify(permissionService).replacePermissions(eq("ling-b"), argThat(map ->
                map != null && map.size() == 1
                        && AccessType.NONE.equals(map.get(Capabilities.CACHE_LOCAL))));
        verifyNoMoreInteractions(permissionService);
    }
}
