package com.lingframe.core.governance.provider;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.GovernanceDecision;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/**
 * {@link GovernancePolicy.ReferenceRule} 治理规则收敛单测。
 * <p>
 * 验证原散在 @LingReference.timeout/fallback 的入参收敛到 YAML references 分区后，
 * {@link StandardGovernancePolicyProvider#applyPolicyOverlay} 在 P2 阶段正确覆到 GovernanceDecision：
 * <ul>
 *   <li>精确方法名匹配</li>
 *   <li>{@code prefix*} 模糊匹配（glob 语义）</li>
 *   <li>三字段（timeoutMs / fallbackValue / retryCount）独立覆</li>
 *   <li>未匹配时不覆（保留低优先级源的值）</li>
 *   <li>invocation 段优先级高于 references 段（同字段命中时后者被覆盖）</li>
 * </ul>
 */
@DisplayName("ReferenceRule 治理规则收敛测试")
class ReferenceRuleGovernanceProviderTest {

    private LocalGovernanceRegistry localRegistry;
    private StandardGovernancePolicyProvider provider;

    public static class TestService {
        public String sendSms(String to) {
            return to;
        }

        public String sendEmail(String to) {
            return to;
        }
    }

    @BeforeEach
    void setUp() {
        localRegistry = mock(LocalGovernanceRegistry.class);
        provider = new StandardGovernancePolicyProvider(localRegistry, Collections.emptyList());
    }

    /** 构造一个带 references 段的 patch 策略，并 mock localRegistry 返回它 */
    private GovernancePolicy patchWithReferences(GovernancePolicy.ReferenceRule... rules) {
        GovernancePolicy patch = mock(GovernancePolicy.class);
        when(patch.getPermissions()).thenReturn(null);
        when(patch.getAudits()).thenReturn(null);
        when(patch.getInvocation()).thenReturn(null);
        when(patch.getReferences()).thenReturn(Arrays.asList(rules));
        when(localRegistry.getPatch("test-ling")).thenReturn(patch);
        return patch;
    }

    private GovernanceDecision resolve(Method method) throws Exception {
        LingRuntime runtime = mock(LingRuntime.class);
        when(runtime.getLingId()).thenReturn("test-ling");
        when(runtime.getInstancePool()).thenReturn(mock(InstancePool.class));
        when(runtime.getInstancePool().getDefault()).thenReturn(null);
        InvocationContext ctx = mock(InvocationContext.class);
        return provider.resolve(runtime, method, ctx);
    }

    @Nested
    @DisplayName("精确匹配")
    class ExactMatch {

        @Test
        @DisplayName("referencePattern 精确方法名命中应覆 timeout/fallback/retry")
        void shouldApplyAllFieldsOnExactMatch() throws Exception {
            patchWithReferences(GovernancePolicy.ReferenceRule.builder()
                    .referencePattern("sendSms")
                    .timeoutMs(3000)
                    .fallbackValue("default-sms")
                    .retryCount(2)
                    .build());

            Method method = TestService.class.getMethod("sendSms", String.class);
            GovernanceDecision decision = resolve(method);

            assertNotNull(decision);
            assertEquals(Duration.ofMillis(3000L), decision.getTimeout(), "timeout 应被 references 覆");
            assertEquals("default-sms", decision.getFallbackValue(), "fallback 应被 references 覆");
            assertEquals(2, decision.getRetryCount(), "retryCount 应被 references 覆");
        }

        @Test
        @DisplayName("referencePattern 与方法名不等时应不覆")
        void shouldNotApplyOnMismatch() throws Exception {
            patchWithReferences(GovernancePolicy.ReferenceRule.builder()
                    .referencePattern("sendSms")
                    .timeoutMs(3000)
                    .build());

            Method method = TestService.class.getMethod("sendEmail", String.class);
            GovernanceDecision decision = resolve(method);

            assertNotNull(decision);
            assertNull(decision.getTimeout(), "未命中方法 timeout 不应被覆");
        }
    }

    @Nested
    @DisplayName("模糊匹配（prefix* glob）")
    class GlobMatch {

        @Test
        @DisplayName("referencePattern 为 send* 应匹配 sendSms 和 sendEmail")
        void shouldMatchByPrefixGlob() throws Exception {
            patchWithReferences(GovernancePolicy.ReferenceRule.builder()
                    .referencePattern("send*")
                    .timeoutMs(5000)
                    .build());

            GovernanceDecision smsDecision = resolve(TestService.class.getMethod("sendSms", String.class));
            GovernanceDecision emailDecision = resolve(TestService.class.getMethod("sendEmail", String.class));

            assertNotNull(smsDecision);
            assertEquals(Duration.ofMillis(5000L), smsDecision.getTimeout(),
                    "send* 应命中 sendSms");
            assertNotNull(emailDecision);
            assertEquals(Duration.ofMillis(5000L), emailDecision.getTimeout(),
                    "send* 应命中 sendEmail");
        }

        @Test
        @DisplayName("referencePattern 为 query* 不应命中 sendSms")
        void shouldNotMatchUnrelatedPrefix() throws Exception {
            patchWithReferences(GovernancePolicy.ReferenceRule.builder()
                    .referencePattern("query*")
                    .timeoutMs(5000)
                    .build());

            GovernanceDecision decision = resolve(TestService.class.getMethod("sendSms", String.class));
            assertNotNull(decision);
            assertNull(decision.getTimeout(), "query* 不应命中 sendSms");
        }
    }

    @Nested
    @DisplayName("字段独立覆与优先级")
    class FieldOverlay {

        @Test
        @DisplayName("仅设部分字段的 ReferenceRule 应只覆被设字段，不动其他")
        void shouldOnlyOverlaySetFields() throws Exception {
            patchWithReferences(GovernancePolicy.ReferenceRule.builder()
                    .referencePattern("sendSms")
                    .timeoutMs(3000)
                    .build()); // fallbackValue/retryCount 为 null

            Method method = TestService.class.getMethod("sendSms", String.class);
            GovernanceDecision decision = resolve(method);

            assertNotNull(decision);
            assertEquals(Duration.ofMillis(3000L), decision.getTimeout(), "被设字段应覆");
            assertNull(decision.getFallbackValue(), "未设字段不应覆");
            assertNull(decision.getRetryCount(), "未设字段不应覆");
        }

        @Test
        @DisplayName("invocation 段同字段命中应覆盖 references 段（被调方策略优先级更高）")
        void invocationShouldOverrideReferences() throws Exception {
            GovernancePolicy patch = mock(GovernancePolicy.class);
            when(patch.getPermissions()).thenReturn(null);
            when(patch.getAudits()).thenReturn(null);
            when(patch.getReferences()).thenReturn(Collections.singletonList(
                    GovernancePolicy.ReferenceRule.builder()
                            .referencePattern("sendSms")
                            .timeoutMs(3000)
                            .build()));
            // invocation 段设 timeout=9000，应覆盖 references 段的 3000
            GovernancePolicy.InvocationPolicy invocation = mock(GovernancePolicy.InvocationPolicy.class);
            when(invocation.getTimeoutMs()).thenReturn(9000);
            when(patch.getInvocation()).thenReturn(invocation);
            when(localRegistry.getPatch("test-ling")).thenReturn(patch);

            Method method = TestService.class.getMethod("sendSms", String.class);
            GovernanceDecision decision = resolve(method);

            assertNotNull(decision);
            assertEquals(Duration.ofMillis(9000L), decision.getTimeout(),
                    "invocation 段应覆盖 references 段的同字段值");
        }
    }

    @Nested
    @DisplayName("健壮性")
    class Robustness {

        @Test
        @DisplayName("referencePattern 为 null 的规则应被跳过不崩")
        void shouldSkipNullPatternRule() throws Exception {
            patchWithReferences(GovernancePolicy.ReferenceRule.builder()
                    .referencePattern(null)
                    .timeoutMs(3000)
                    .build());

            Method method = TestService.class.getMethod("sendSms", String.class);
            GovernanceDecision decision = resolve(method);

            assertNotNull(decision);
            assertNull(decision.getTimeout(), "null pattern 规则不应命中任何方法");
        }

        @Test
        @DisplayName("references 为空列表时应不覆且不崩")
        void shouldHandleEmptyReferencesList() throws Exception {
            GovernancePolicy patch = mock(GovernancePolicy.class);
            when(patch.getPermissions()).thenReturn(null);
            when(patch.getAudits()).thenReturn(null);
            when(patch.getReferences()).thenReturn(Collections.emptyList());
            when(patch.getInvocation()).thenReturn(null);
            when(localRegistry.getPatch("test-ling")).thenReturn(patch);

            Method method = TestService.class.getMethod("sendSms", String.class);
            GovernanceDecision decision = resolve(method);

            assertNotNull(decision, "空 references 不应导致 decision 为 null");
            assertNull(decision.getTimeout());
        }
    }
}
