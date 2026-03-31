package com.lingframe.core.governance.provider;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StandardGovernancePolicyProvider 测试")
class StandardGovernancePolicyProviderTest {

    @Mock
    private LocalGovernanceRegistry localRegistry;

    @Mock
    private LingRuntime runtime;

    @Mock
    private InstancePool instancePool;

    @Mock
    private LingInstance instance;

    @Nested
    @DisplayName("调用治理收敛")
    class InvocationPolicyTests {

        @Test
        @DisplayName("补丁应覆盖调用治理参数，但保留注解提供的权限语义")
        void shouldOverlayInvocationPatchWithoutBreakingAnnotationDecision() throws Exception {
            GovernancePolicy staticPolicy = new GovernancePolicy();
            staticPolicy.getInvocation().setTimeoutMs(800);
            staticPolicy.getInvocation().setRateLimitPerSecond(20);
            staticPolicy.getInvocation().setMaxConcurrentThreads(4);

            GovernancePolicy patch = new GovernancePolicy();
            patch.getInvocation().setRateLimitPerSecond(5);

            DemoDefinitionHolder holder = new DemoDefinitionHolder(staticPolicy);

            when(runtime.getLingId()).thenReturn("demo-ling");
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.getDefinition()).thenReturn(holder.definition);
            when(localRegistry.getPatch("demo-ling")).thenReturn(patch);

            StandardGovernancePolicyProvider provider =
                    new StandardGovernancePolicyProvider(localRegistry, Collections.emptyList());
            Method method = DemoService.class.getMethod("call");
            InvocationContext context = InvocationContext.obtain();

            GovernanceDecision decision = provider.resolve(runtime, method, context);

            assertEquals("anno:call", decision.getRequiredPermission());
            assertEquals(AccessType.EXECUTE, decision.getAccessType());
            assertEquals(true, decision.getAuditEnabled());
            assertEquals("ANN_CALL", decision.getAuditAction());
            assertEquals(800L, decision.getTimeout().toMillis());
            assertEquals(Integer.valueOf(5), decision.getRateLimitPerSecond());
            assertEquals(Integer.valueOf(4), decision.getMaxConcurrentThreads());
            assertTrue(decision.getSource().startsWith("Annotation"));
            context.recycle();
        }

        @Test
        @DisplayName("补丁权限规则命中时应覆盖低优先级定义")
        void shouldUsePatchPermissionRuleBeforeLowerPrioritySources() throws Exception {
            GovernancePolicy staticPolicy = new GovernancePolicy();
            staticPolicy.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                    .methodPattern("call")
                    .permissionId("ling:definition")
                    .build());

            GovernancePolicy patch = new GovernancePolicy();
            patch.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                    .methodPattern("call")
                    .permissionId("ling:patch")
                    .build());
            patch.getInvocation().setTimeoutMs(1200);

            DemoDefinitionHolder holder = new DemoDefinitionHolder(staticPolicy);

            when(runtime.getLingId()).thenReturn("demo-ling");
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.getDefinition()).thenReturn(holder.definition);
            when(localRegistry.getPatch("demo-ling")).thenReturn(patch);

            StandardGovernancePolicyProvider provider =
                    new StandardGovernancePolicyProvider(localRegistry, Collections.emptyList());
            Method method = DemoService.class.getMethod("call");
            InvocationContext context = InvocationContext.obtain();

            GovernanceDecision decision = provider.resolve(runtime, method, context);

            assertEquals("ling:patch", decision.getRequiredPermission());
            assertEquals(1200L, decision.getTimeout().toMillis());
            assertTrue(decision.getSource().startsWith("Patch"));
            context.recycle();
        }
    }

    private static final class DemoDefinitionHolder {
        private final com.lingframe.api.config.LingDefinition definition;

        private DemoDefinitionHolder(GovernancePolicy governancePolicy) {
            this.definition = new com.lingframe.api.config.LingDefinition();
            this.definition.setId("demo-ling");
            this.definition.setVersion("1.0.0");
            this.definition.setGovernance(governancePolicy);
        }
    }

    private static class DemoService {
        @RequiresPermission("anno:call")
        @Auditable(action = "ANN_CALL")
        public void call() {
        }
    }
}
