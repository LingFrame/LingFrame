package com.lingframe.core.governance.provider;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.spi.GovernanceDecision;
import com.lingframe.core.governance.LingCoreGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("StandardGovernancePolicyProvider 测试")
class StandardGovernancePolicyProviderTest {

    private StandardGovernancePolicyProvider provider;
    private LocalGovernanceRegistry localRegistry;

    @BeforeEach
    void setUp() {
        localRegistry = mock(LocalGovernanceRegistry.class);
        provider = new StandardGovernancePolicyProvider(localRegistry, Collections.emptyList());
    }

    @Test
    @DisplayName("getOrder 返回 100")
    void shouldReturnOrder() {
        assertEquals(100, provider.getOrder());
    }

    @Test
    @DisplayName("resolve null runtime 时使用 unknown 作为 lingId")
    void shouldResolveWithNullRuntime() throws Exception {
        Method method = TestService.class.getMethod("echo", String.class);
        InvocationContext ctx = mock(InvocationContext.class);

        GovernanceDecision decision = provider.resolve(null, method, ctx);
        assertNotNull(decision);
    }

    @Test
    @DisplayName("resolve 正常 runtime 返回决策")
    void shouldResolveWithRuntime() throws Exception {
        LingRuntime runtime = mock(LingRuntime.class);
        LingInstance instance = mock(LingInstance.class);
        when(runtime.getLingId()).thenReturn("test-ling");
        when(runtime.getInstancePool()).thenReturn(mock(InstancePool.class));
        when(runtime.getInstancePool().getDefault()).thenReturn(instance);
        when(instance.getDefinition()).thenReturn(null);

        Method method = TestService.class.getMethod("echo", String.class);
        InvocationContext ctx = mock(InvocationContext.class);

        GovernanceDecision decision = provider.resolve(runtime, method, ctx);
        assertNotNull(decision);
    }

    @Test
    @DisplayName("resolve 带 LingCoreGovernanceRule 时应用规则")
    void shouldApplyCoreRule() throws Exception {
        LingCoreGovernanceRule rule = mock(LingCoreGovernanceRule.class);
        when(rule.getPattern()).thenReturn("test-ling.echo");
        when(rule.getPermission()).thenReturn("admin");
        when(rule.getAccessType()).thenReturn(AccessType.WRITE);

        StandardGovernancePolicyProvider providerWithRule = new StandardGovernancePolicyProvider(
                localRegistry, Arrays.asList(rule));

        LingRuntime runtime = mock(LingRuntime.class);
        when(runtime.getLingId()).thenReturn("test-ling");
        when(runtime.getInstancePool()).thenReturn(mock(InstancePool.class));
        when(runtime.getInstancePool().getDefault()).thenReturn(null);

        Method method = TestService.class.getMethod("echo", String.class);
        InvocationContext ctx = mock(InvocationContext.class);

        GovernanceDecision decision = providerWithRule.resolve(runtime, method, ctx);
        assertNotNull(decision);
        assertEquals("admin", decision.getRequiredPermission());
        assertEquals(AccessType.WRITE, decision.getAccessType());
    }

    @Test
    @DisplayName("resolve 带 patch 策略时覆盖")
    void shouldApplyPatchPolicy() throws Exception {
        GovernancePolicy patch = mock(GovernancePolicy.class);
        GovernancePolicy.PermissionRule permRule = mock(GovernancePolicy.PermissionRule.class);
        when(permRule.getMethodPattern()).thenReturn("echo");
        when(permRule.getPermissionId()).thenReturn("patch-perm");
        when(patch.getPermissions()).thenReturn(Arrays.asList(permRule));
        when(patch.getAudits()).thenReturn(null);
        when(patch.getInvocation()).thenReturn(null);

        when(localRegistry.getPatch("test-ling")).thenReturn(patch);

        LingRuntime runtime = mock(LingRuntime.class);
        when(runtime.getLingId()).thenReturn("test-ling");
        when(runtime.getInstancePool()).thenReturn(mock(InstancePool.class));
        when(runtime.getInstancePool().getDefault()).thenReturn(null);

        Method method = TestService.class.getMethod("echo", String.class);
        InvocationContext ctx = mock(InvocationContext.class);

        GovernanceDecision decision = provider.resolve(runtime, method, ctx);
        assertNotNull(decision);
        assertEquals("patch-perm", decision.getRequiredPermission());
    }

    @Test
    @DisplayName("resolve 带 invocation policy 时设置超时")
    void shouldApplyInvocationPolicy() throws Exception {
        GovernancePolicy patch = mock(GovernancePolicy.class);
        GovernancePolicy.InvocationPolicy invocation = mock(GovernancePolicy.InvocationPolicy.class);
        when(invocation.getTimeoutMs()).thenReturn(5000);
        when(invocation.getRateLimitPerSecond()).thenReturn(100);
        when(invocation.getMaxConcurrentThreads()).thenReturn(10);
        when(invocation.getRetryCount()).thenReturn(3);
        when(invocation.getFallbackValue()).thenReturn("fallback");
        when(invocation.getCpuBudgetMsPerMinute()).thenReturn(1000);
        when(invocation.getMemoryBudgetMb()).thenReturn(256);
        when(patch.getPermissions()).thenReturn(null);
        when(patch.getAudits()).thenReturn(null);
        when(patch.getInvocation()).thenReturn(invocation);

        when(localRegistry.getPatch("test-ling")).thenReturn(patch);

        LingRuntime runtime = mock(LingRuntime.class);
        when(runtime.getLingId()).thenReturn("test-ling");
        when(runtime.getInstancePool()).thenReturn(mock(InstancePool.class));
        when(runtime.getInstancePool().getDefault()).thenReturn(null);

        Method method = TestService.class.getMethod("echo", String.class);
        InvocationContext ctx = mock(InvocationContext.class);

        GovernanceDecision decision = provider.resolve(runtime, method, ctx);
        assertNotNull(decision);
        assertEquals(Duration.ofMillis(5000L), decision.getTimeout());
    }

    public static class TestService {
        public String echo(String msg) {
            return msg;
        }
    }
}
