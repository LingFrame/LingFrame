package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DisplayName("GovernancePermissionSynchronizer 测试")
class GovernancePermissionSynchronizerTest {

    @Test
    @DisplayName("syncPolicy 应使用持久化能力规则覆盖运行时权限")
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
        InOrder inOrder = inOrder(permissionService);
        inOrder.verify(permissionService).removeLing("demo-ling");
        inOrder.verify(permissionService).grant("demo-ling", Capabilities.STORAGE_SQL, AccessType.WRITE);
        inOrder.verify(permissionService).grant("demo-ling", Capabilities.CACHE_LOCAL, AccessType.READ);
        verifyNoMoreInteractions(permissionService);
    }

    @Test
    @DisplayName("syncPolicy 在持久化策略为空时应清空运行时权限")
    void syncPolicyShouldClearRuntimePermissionsWhenPersistedPolicyIsEmpty() {
        PermissionService permissionService = mock(PermissionService.class);

        int synced = GovernancePermissionSynchronizer.syncPolicy("demo-ling", new GovernancePolicy(), permissionService);

        assertEquals(0, synced);
        InOrder inOrder = inOrder(permissionService);
        inOrder.verify(permissionService).removeLing("demo-ling");
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
        InOrder inOrder = inOrder(permissionService);
        inOrder.verify(permissionService).removeLing("demo-ling");
        inOrder.verify(permissionService).grant("demo-ling", Capabilities.CACHE_LOCAL, AccessType.WRITE);
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
        InOrder inOrder = inOrder(permissionService);
        inOrder.verify(permissionService).removeLing("ling-a");
        inOrder.verify(permissionService).grant("ling-a", Capabilities.STORAGE_SQL, AccessType.READ);
        inOrder.verify(permissionService).removeLing("ling-b");
        inOrder.verify(permissionService).grant("ling-b", Capabilities.CACHE_LOCAL, AccessType.NONE);
        verifyNoMoreInteractions(permissionService);
    }
}
