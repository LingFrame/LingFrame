package com.lingframe.starter.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
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
 * 重点覆盖 applyTo 的空值短路、静态策略解析、patch 合并与
 * timeout / rateLimit / maxConcurrentThreads 三项治理参数下发。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntryInvocationGovernanceResolver 补充测试")
class EntryInvocationGovernanceResolverSupplementTest {

    @Mock
    private LingRepository lingRepository;
    @Mock
    private LocalGovernanceRegistry governanceRegistry;
    @Mock
    private InvocationContext context;
    @Mock
    private InvocationGovernanceState governanceState;
    @Mock
    private LingRuntime runtime;
    @Mock
    private InstancePool instancePool;
    @Mock
    private LingInstance instance;
    @Mock
    private LingDefinition definition;

    @Test
    @DisplayName("applyTo 在 context 为 null 时应直接返回")
    void shouldReturnWhenContextNull() {
        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(lingRepository, governanceRegistry);

        resolver.applyTo(null, "ling-a");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在 lingId 为 null 或空时应直接返回")
    void shouldReturnWhenLingIdNullOrEmpty() {
        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(lingRepository, governanceRegistry);

        resolver.applyTo(context, null);
        resolver.applyTo(context, "");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在无静态策略且无 patch 时应不下发任何治理参数")
    void shouldSkipWhenNoStaticPolicyAndNoPatch() {
        when(lingRepository.getRuntime("ling-a")).thenReturn(null);
        when(governanceRegistry.getPatch("ling-a")).thenReturn(null);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(lingRepository, governanceRegistry);

        resolver.applyTo(context, "ling-a");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在 InvocationPolicy 所有字段为 null 时应不下发治理参数")
    void shouldSkipWhenAllInvocationFieldsNull() {
        // builder().build() 产生的 invocation 为 new InvocationPolicy()，
        // 其 timeoutMs / rateLimitPerSecond / maxConcurrentThreads 均为 null，
        // 因此 context.governance() 不会被调用
        GovernancePolicy policy = GovernancePolicy.builder().build();
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getDefault()).thenReturn(instance);
        when(instance.getDefinition()).thenReturn(definition);
        when(definition.getGovernance()).thenReturn(policy);
        when(governanceRegistry.getPatch("ling-a")).thenReturn(null);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(lingRepository, governanceRegistry);

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
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getDefault()).thenReturn(instance);
        when(instance.getDefinition()).thenReturn(definition);
        when(definition.getGovernance()).thenReturn(policy);
        when(governanceRegistry.getPatch("ling-a")).thenReturn(null);
        when(context.governance()).thenReturn(governanceState);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(lingRepository, governanceRegistry);

        resolver.applyTo(context, "ling-a");

        verify(governanceState).setTimeoutMs(1000);
        verify(governanceState).setRateLimitPerSecond(50);
        verify(governanceState).setMaxConcurrentThreads(4);
    }

    @Test
    @DisplayName("applyTo 在 InstancePool 为 null 时应安全跳过静态策略解析")
    void shouldSkipStaticPolicyWhenInstancePoolNull() {
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(null);
        when(governanceRegistry.getPatch("ling-a")).thenReturn(null);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(lingRepository, governanceRegistry);

        resolver.applyTo(context, "ling-a");

        verify(context, never()).governance();
    }

    @Test
    @DisplayName("applyTo 在治理器为 null 时应仅依赖静态策略")
    void shouldRelyOnStaticPolicyWhenRegistryNull() {
        GovernancePolicy policy = GovernancePolicy.builder()
                .invocation(GovernancePolicy.InvocationPolicy.builder()
                        .timeoutMs(2000)
                        .build())
                .build();
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getDefault()).thenReturn(instance);
        when(instance.getDefinition()).thenReturn(definition);
        when(definition.getGovernance()).thenReturn(policy);
        when(context.governance()).thenReturn(governanceState);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(lingRepository, null);

        resolver.applyTo(context, "ling-a");

        verify(governanceState).setTimeoutMs(2000);
        // 仅设置 timeout，未设置 rateLimit / maxConcurrent
        verify(governanceState, never()).setRateLimitPerSecond(any());
        verify(governanceState, never()).setMaxConcurrentThreads(any());
    }

    @Test
    @DisplayName("applyTo 在 instance 或 definition 为 null 时应安全跳过")
    void shouldSkipWhenInstanceOrDefinitionNull() {
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getDefault()).thenReturn(null);
        when(governanceRegistry.getPatch("ling-a")).thenReturn(null);

        EntryInvocationGovernanceResolver resolver =
                new EntryInvocationGovernanceResolver(lingRepository, governanceRegistry);

        resolver.applyTo(context, "ling-a");

        verify(context, never()).governance();
    }
}
