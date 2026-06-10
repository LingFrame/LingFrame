package com.lingframe.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GovernancePolicy 及内部类测试
 */
class GovernancePolicyTest {

    @Nested
    @DisplayName("copy 深拷贝")
    class CopyTest {

        @Test
        @DisplayName("copy 产生独立副本，修改原对象不影响副本")
        void shouldDeepCopy() {
            GovernancePolicy original = GovernancePolicy.builder()
                    .collaborationMode(CollaborationMode.FULL_ACTIVE)
                    .permissions(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.PermissionRule.builder()
                                    .methodPattern("com.example.*")
                                    .permissionId("admin")
                                    .build())))
                    .capabilities(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.CapabilityRule.builder()
                                    .capability("db")
                                    .accessType("WRITE")
                                    .build())))
                    .audits(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.AuditRule.builder()
                                    .methodPattern("com.example.*")
                                    .action("write")
                                    .enabled(true)
                                    .build())))
                    .invocation(GovernancePolicy.InvocationPolicy.builder()
                            .timeoutMs(5000)
                            .rateLimitPerSecond(100)
                            .build())
                    .build();

            GovernancePolicy copy = original.copy();

            // 修改原对象
            original.getPermissions().clear();
            original.getCapabilities().clear();
            original.getAudits().clear();
            original.getInvocation().setTimeoutMs(9999);
            original.setCollaborationMode(CollaborationMode.PASSIVE);

            // 副本不受影响
            assertEquals(1, copy.getPermissions().size());
            assertEquals(1, copy.getCapabilities().size());
            assertEquals(1, copy.getAudits().size());
            assertEquals(5000, copy.getInvocation().getTimeoutMs());
            assertEquals(CollaborationMode.FULL_ACTIVE, copy.getCollaborationMode());
        }

        @Test
        @DisplayName("空策略 copy 不报错")
        void shouldCopyEmptyPolicy() {
            GovernancePolicy empty = new GovernancePolicy();
            GovernancePolicy copy = empty.copy();
            assertNotNull(copy);
            assertNotNull(copy.getPermissions());
            assertNotNull(copy.getCapabilities());
            assertNotNull(copy.getAudits());
        }
    }

    @Nested
    @DisplayName("applyPatch 合并补丁")
    class ApplyPatchTest {

        @Test
        @DisplayName("null 补丁不修改原策略")
        void shouldIgnoreNullPatch() {
            GovernancePolicy policy = GovernancePolicy.builder()
                    .collaborationMode(CollaborationMode.FULL_ACTIVE)
                    .build();
            policy.applyPatch(null);
            assertEquals(CollaborationMode.FULL_ACTIVE, policy.getCollaborationMode());
        }

        @Test
        @DisplayName("非空列表覆盖原列表")
        void shouldOverrideWithNonEmptyList() {
            GovernancePolicy base = GovernancePolicy.builder()
                    .permissions(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.PermissionRule.builder()
                                    .methodPattern("old.*")
                                    .permissionId("old-perm")
                                    .build())))
                    .build();

            GovernancePolicy patch = GovernancePolicy.builder()
                    .permissions(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.PermissionRule.builder()
                                    .methodPattern("new.*")
                                    .permissionId("new-perm")
                                    .build())))
                    .build();

            base.applyPatch(patch);
            assertEquals(1, base.getPermissions().size());
            assertEquals("new.*", base.getPermissions().get(0).getMethodPattern());
        }

        @Test
        @DisplayName("空列表不覆盖原列表")
        void shouldNotOverrideWithEmptyList() {
            GovernancePolicy base = GovernancePolicy.builder()
                    .permissions(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.PermissionRule.builder()
                                    .methodPattern("keep.*")
                                    .permissionId("keep-perm")
                                    .build())))
                    .build();

            GovernancePolicy patch = GovernancePolicy.builder()
                    .permissions(new ArrayList<>())
                    .build();

            base.applyPatch(patch);
            assertEquals(1, base.getPermissions().size());
            assertEquals("keep.*", base.getPermissions().get(0).getMethodPattern());
        }

        @Test
        @DisplayName("InvocationPolicy 字段级非 null 覆盖")
        void shouldPatchInvocationFields() {
            GovernancePolicy base = GovernancePolicy.builder()
                    .invocation(GovernancePolicy.InvocationPolicy.builder()
                            .timeoutMs(1000)
                            .rateLimitPerSecond(50)
                            .maxConcurrentThreads(4)
                            .build())
                    .build();

            GovernancePolicy patch = GovernancePolicy.builder()
                    .invocation(GovernancePolicy.InvocationPolicy.builder()
                            .timeoutMs(2000)
                            .build())
                    .build();

            base.applyPatch(patch);
            assertEquals(2000, base.getInvocation().getTimeoutMs());
            assertEquals(50, base.getInvocation().getRateLimitPerSecond());
            assertEquals(4, base.getInvocation().getMaxConcurrentThreads());
        }

        @Test
        @DisplayName("invocation 为默认值时 patch 能覆盖字段")
        void shouldPatchInvocationFieldsWhenDefault() {
            GovernancePolicy base = new GovernancePolicy();
            assertNotNull(base.getInvocation()); // @Builder.Default 初始化

            GovernancePolicy patch = GovernancePolicy.builder()
                    .invocation(GovernancePolicy.InvocationPolicy.builder()
                            .timeoutMs(3000)
                            .build())
                    .build();

            base.applyPatch(patch);
            assertEquals(3000, base.getInvocation().getTimeoutMs());
        }
    }

    @Nested
    @DisplayName("merge 静态方法")
    class MergeTest {

        @Test
        @DisplayName("base 为 null 时创建空策略再合并")
        void shouldMergeWithNullBase() {
            GovernancePolicy patch = GovernancePolicy.builder()
                    .permissions(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.PermissionRule.builder()
                                    .methodPattern("com.test.*")
                                    .permissionId("test-perm")
                                    .build())))
                    .build();

            GovernancePolicy result = GovernancePolicy.merge(null, patch);
            assertEquals(1, result.getPermissions().size());
        }

        @Test
        @DisplayName("merge 不修改原 base 对象")
        void shouldNotModifyOriginalBase() {
            GovernancePolicy base = GovernancePolicy.builder()
                    .permissions(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.PermissionRule.builder()
                                    .methodPattern("old.*")
                                    .permissionId("old-perm")
                                    .build())))
                    .build();

            GovernancePolicy patch = GovernancePolicy.builder()
                    .permissions(new ArrayList<>(Arrays.asList(
                            GovernancePolicy.PermissionRule.builder()
                                    .methodPattern("new.*")
                                    .permissionId("new-perm")
                                    .build())))
                    .build();

            GovernancePolicy result = GovernancePolicy.merge(base, patch);
            assertEquals(1, base.getPermissions().size());
            assertEquals("old.*", base.getPermissions().get(0).getMethodPattern());
            assertEquals(1, result.getPermissions().size());
            assertEquals("new.*", result.getPermissions().get(0).getMethodPattern());
        }
    }

    @Nested
    @DisplayName("内部 Rule 类 copy")
    class RuleCopyTest {

        @Test
        @DisplayName("PermissionRule.copy 深拷贝")
        void shouldCopyPermissionRule() {
            GovernancePolicy.PermissionRule rule = GovernancePolicy.PermissionRule.builder()
                    .methodPattern("com.test.*")
                    .permissionId("test-perm")
                    .build();
            GovernancePolicy.PermissionRule copy = rule.copy();
            assertEquals(rule.getMethodPattern(), copy.getMethodPattern());
            assertEquals(rule.getPermissionId(), copy.getPermissionId());
        }

        @Test
        @DisplayName("CapabilityRule.copy 深拷贝")
        void shouldCopyCapabilityRule() {
            GovernancePolicy.CapabilityRule rule = GovernancePolicy.CapabilityRule.builder()
                    .capability("cache")
                    .accessType("READ")
                    .build();
            GovernancePolicy.CapabilityRule copy = rule.copy();
            assertEquals(rule.getCapability(), copy.getCapability());
            assertEquals(rule.getAccessType(), copy.getAccessType());
        }

        @Test
        @DisplayName("AuditRule.copy 深拷贝")
        void shouldCopyAuditRule() {
            GovernancePolicy.AuditRule rule = GovernancePolicy.AuditRule.builder()
                    .methodPattern("com.test.*")
                    .action("read")
                    .enabled(true)
                    .build();
            GovernancePolicy.AuditRule copy = rule.copy();
            assertEquals(rule.getMethodPattern(), copy.getMethodPattern());
            assertEquals(rule.getAction(), copy.getAction());
            assertTrue(copy.isEnabled());
        }

        @Test
        @DisplayName("InvocationPolicy.copy 深拷贝")
        void shouldCopyInvocationPolicy() {
            GovernancePolicy.InvocationPolicy policy = GovernancePolicy.InvocationPolicy.builder()
                    .timeoutMs(1000)
                    .rateLimitPerSecond(100)
                    .maxConcurrentThreads(8)
                    .retryCount(3)
                    .fallbackValue("default")
                    .cpuBudgetMsPerMinute(5000)
                    .memoryBudgetMb(256)
                    .build();
            GovernancePolicy.InvocationPolicy copy = policy.copy();
            assertEquals(1000, copy.getTimeoutMs());
            assertEquals(100, copy.getRateLimitPerSecond());
            assertEquals(8, copy.getMaxConcurrentThreads());
            assertEquals(3, copy.getRetryCount());
            assertEquals("default", copy.getFallbackValue());
            assertEquals(5000, copy.getCpuBudgetMsPerMinute());
            assertEquals(256, copy.getMemoryBudgetMb());
        }

        @Test
        @DisplayName("InvocationPolicy.applyPatch null 不修改")
        void shouldIgnoreNullPatch() {
            GovernancePolicy.InvocationPolicy policy = GovernancePolicy.InvocationPolicy.builder()
                    .timeoutMs(1000)
                    .build();
            policy.applyPatch(null);
            assertEquals(1000, policy.getTimeoutMs());
        }
    }
}
