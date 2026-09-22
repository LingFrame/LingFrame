package com.lingframe.starter.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationGovernanceState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EntryInvocationGovernanceResolver} 补充测试。
 * <p>
 * 策略解析已下沉到 {@link GovernanceAdminService#getEffectivePolicy}，
 * 此处只验证 resolver 把内核算出的生效策略正确下发到 {@link InvocationContext#governance()} 分区，
 * 以及空值短路、无策略、字段全 null 等边界。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntryInvocationGovernanceResolver 补充测试")
class EntryInvocationGovernanceResolverSupplementTest {

    @Mock
    private GovernanceAdminService governanceAdmin;
    @Mock
    private InvocationContext context;
    @Mock
    private InvocationGovernanceState governanceState;

    @Test
    @DisplayName("applyTo 在 context 为 null 时应直接返回")
    void shouldReturnWhenContextNull() {
        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(governanceAdmin);

        resolver.applyTo(null, "ling-a");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在 lingId 为 null 或空时应直接返回")
    void shouldReturnWhenLingIdNullOrEmpty() {
        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(governanceAdmin);

        resolver.applyTo(context, null);
        resolver.applyTo(context, "");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在 getEffectivePolicy 返回 null 时应不下发任何治理参数")
    void shouldSkipWhenNoEffectivePolicy() {
        when(governanceAdmin.getEffectivePolicy("ling-a")).thenReturn(null);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(governanceAdmin);

        resolver.applyTo(context, "ling-a");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在 InvocationPolicy 所有字段为 null 时应不下发治理参数")
    void shouldSkipWhenAllInvocationFieldsNull() {
        // builder().build() 产生的 invocation 其 timeoutMs / rateLimitPerSecond / maxConcurrentThreads 均为 null，
        // 因此 context.governance() 不会被调用
        GovernancePolicy policy = GovernancePolicy.builder().build();
        when(governanceAdmin.getEffectivePolicy("ling-a")).thenReturn(policy);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(governanceAdmin);

        resolver.applyTo(context, "ling-a");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 应将 timeout/rateLimit/maxConcurrent 下发到 context.governance")
    void shouldApplyTimeoutRateLimitAndMaxConcurrent() {
        GovernancePolicy policy = GovernancePolicy.builder()
                .invocation(GovernancePolicy.InvocationPolicy.builder()
                        .timeoutMs(1000)
                        .rateLimitPerSecond(50)
                        .maxConcurrentThreads(4)
                        .build())
                .build();
        when(governanceAdmin.getEffectivePolicy("ling-a")).thenReturn(policy);
        when(context.governance()).thenReturn(governanceState);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(governanceAdmin);

        resolver.applyTo(context, "ling-a");

        verify(governanceState).setTimeoutMs(1000);
        verify(governanceState).setRateLimitPerSecond(50);
        verify(governanceState).setMaxConcurrentThreads(4);
    }

    @Test
    @DisplayName("applyTo 在 invocation 为 null 时应安全跳过下发")
    void shouldSkipWhenInvocationNull() {
        GovernancePolicy policy = GovernancePolicy.builder()
                .invocation(null)
                .build();
        when(governanceAdmin.getEffectivePolicy("ling-a")).thenReturn(policy);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(governanceAdmin);

        resolver.applyTo(context, "ling-a");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在治理服务为 null 时应安全跳过（防御性 null 兜底）")
    void shouldSkipWhenAdminNull() {
        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(null);

        resolver.applyTo(context, "ling-a");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在只有 timeout 非空时应仅下发 timeout")
    void shouldApplyOnlyTimeoutWhenPresent() {
        GovernancePolicy policy = GovernancePolicy.builder()
                .invocation(GovernancePolicy.InvocationPolicy.builder()
                        .timeoutMs(2000)
                        .build())
                .build();
        when(governanceAdmin.getEffectivePolicy("ling-a")).thenReturn(policy);
        when(context.governance()).thenReturn(governanceState);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(governanceAdmin);

        resolver.applyTo(context, "ling-a");

        verify(governanceState).setTimeoutMs(2000);
        // 仅设置 timeout，未设置 rateLimit / maxConcurrent
        verify(governanceState, never()).setRateLimitPerSecond(any());
        verify(governanceState, never()).setMaxConcurrentThreads(any());
    }
}
