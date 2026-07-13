package com.lingframe.core.pipeline;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link InvocationPolicyPrefillFilter} 预填充语义单测。
 * <p>
 * 验证：在 RESILIENCE 之前把灵元级 effective policy 的 invocation 字段
 * 预填到 ctx.governance()，守护"ctx 为唯一通行证"原则。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvocationPolicyPrefillFilter 预填充测试")
class InvocationPolicyPrefillFilterTest {

    @Mock
    private LingRepository lingRepository;

    @Mock
    private LingRuntime lingRuntime;

    @Mock
    private LingInstance lingInstance;

    @Mock
    private LingDefinition lingDefinition;

    @Mock
    private LingFilterChain filterChain;

    private InvocationContext context;

    @BeforeEach
    void setUp() {
        context = InvocationContext.obtain();
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        lenient().when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
        lenient().when(lingRuntime.getInstancePool()).thenReturn(mock(InstancePool.class));
        lenient().when(lingRuntime.getInstancePool().getDefault()).thenReturn(lingInstance);
        lenient().when(lingInstance.getDefinition()).thenReturn(lingDefinition);
    }

    @AfterEach
    void tearDown() {
        context.reset();
    }

    @Test
    @DisplayName("patch 有 timeout 时预填到 ctx.governance()")
    void prefillsTimeoutFromPatch() throws Throwable {
        // 静态策略无 invocation 字段
        when(lingDefinition.getGovernance()).thenReturn(new GovernancePolicy());
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        GovernancePolicy patch = new GovernancePolicy();
        patch.setInvocation(GovernancePolicy.InvocationPolicy.builder().timeoutMs(5000).build());
        when(registry.getPatch("demo-ling")).thenReturn(patch);

        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, registry);
        filter.doFilter(context, filterChain);

        assertEquals(5000, context.governance().getTimeoutMs());
    }

    @Test
    @DisplayName("patch 有 rateLimit 时预填到 ctx.governance()")
    void prefillsRateLimitFromPatch() throws Throwable {
        when(lingDefinition.getGovernance()).thenReturn(new GovernancePolicy());
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        GovernancePolicy patch = new GovernancePolicy();
        patch.setInvocation(GovernancePolicy.InvocationPolicy.builder().rateLimitPerSecond(100).build());
        when(registry.getPatch("demo-ling")).thenReturn(patch);

        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, registry);
        filter.doFilter(context, filterChain);

        assertEquals(100, context.governance().getRateLimitPerSecond());
    }

    @Test
    @DisplayName("无 patch 时回退静态策略")
    void fallsBackToStaticPolicyWhenNoPatch() throws Throwable {
        GovernancePolicy staticPolicy = new GovernancePolicy();
        staticPolicy.setInvocation(GovernancePolicy.InvocationPolicy.builder()
                .timeoutMs(3000)
                .maxConcurrentThreads(10)
                .build());
        when(lingDefinition.getGovernance()).thenReturn(staticPolicy);
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        when(registry.getPatch("demo-ling")).thenReturn(null);

        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, registry);
        filter.doFilter(context, filterChain);

        assertEquals(3000, context.governance().getTimeoutMs());
        assertEquals(10, context.governance().getMaxConcurrentThreads());
    }

    @Test
    @DisplayName("无静态策略且无 patch 时不改 ctx")
    void doesNotOverwriteWhenEffectiveIsNull() throws Throwable {
        when(lingDefinition.getGovernance()).thenReturn(null);
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        when(registry.getPatch("demo-ling")).thenReturn(null);

        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, registry);
        filter.doFilter(context, filterChain);

        assertNull(context.governance().getTimeoutMs());
        assertNull(context.governance().getRateLimitPerSecond());
        assertNull(context.governance().getMaxConcurrentThreads());
    }

    @Test
    @DisplayName("patch 非 null 字段覆盖静态字段")
    void patchOverridesStaticField() throws Throwable {
        GovernancePolicy staticPolicy = new GovernancePolicy();
        staticPolicy.setInvocation(GovernancePolicy.InvocationPolicy.builder()
                .timeoutMs(3000)
                .rateLimitPerSecond(50)
                .build());
        when(lingDefinition.getGovernance()).thenReturn(staticPolicy);
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        GovernancePolicy patch = new GovernancePolicy();
        // patch 只覆盖 timeout，rateLimit 保留静态值
        patch.setInvocation(GovernancePolicy.InvocationPolicy.builder().timeoutMs(8000).build());
        when(registry.getPatch("demo-ling")).thenReturn(patch);

        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, registry);
        filter.doFilter(context, filterChain);

        assertEquals(8000, context.governance().getTimeoutMs());
        assertEquals(50, context.governance().getRateLimitPerSecond());
    }

    @Test
    @DisplayName("effective policy 的 null 字段不填充，保留 ctx 现有值")
    void skipsNullFields() throws Throwable {
        // 预先设置 ctx.governance() 的值，模拟早期 filter 已填充
        context.governance().setTimeoutMs(9999);

        GovernancePolicy staticPolicy = new GovernancePolicy();
        staticPolicy.setInvocation(GovernancePolicy.InvocationPolicy.builder()
                .rateLimitPerSecond(100)
                .build());
        when(lingDefinition.getGovernance()).thenReturn(staticPolicy);
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        when(registry.getPatch("demo-ling")).thenReturn(null);

        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, registry);
        filter.doFilter(context, filterChain);

        // timeoutMs 为 null（effective 中无此字段），保留 ctx 现有值 9999
        assertEquals(9999, context.governance().getTimeoutMs());
        // rateLimitPerSecond 有值，被填充
        assertEquals(100, context.governance().getRateLimitPerSecond());
    }

    @Test
    @DisplayName("registry 为 null 时回退静态策略（兼容旧装配）")
    void handlesNullRegistry() throws Throwable {
        GovernancePolicy staticPolicy = new GovernancePolicy();
        staticPolicy.setInvocation(GovernancePolicy.InvocationPolicy.builder()
                .timeoutMs(2000)
                .build());
        when(lingDefinition.getGovernance()).thenReturn(staticPolicy);

        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, null);
        filter.doFilter(context, filterChain);

        assertEquals(2000, context.governance().getTimeoutMs());
    }

    @Test
    @DisplayName("getOrder 返回 POLICY_PREFILL 阶段值")
    void getOrderReturnsPolicyPrefillPhase() {
        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, null);
        assertEquals(FilterPhase.POLICY_PREFILL, filter.getOrder());
    }

    @Test
    @DisplayName("新格式 __provider__: FQSID 应优先读 targetLingId 查治理策略")
    void prefillsFromTargetLingIdWhenProviderFqsid() throws Throwable {
        // 覆盖 setUp 的旧格式 FQSID，模拟 ContractProviderRoutingFilter 已解析出真实 lingId
        context.setServiceFQSID("__provider__:com.example.DemoService");
        context.setTargetLingId("demo-ling");

        when(lingDefinition.getGovernance()).thenReturn(new GovernancePolicy());
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        GovernancePolicy patch = new GovernancePolicy();
        patch.setInvocation(GovernancePolicy.InvocationPolicy.builder().timeoutMs(5000).build());
        when(registry.getPatch("demo-ling")).thenReturn(patch);

        InvocationPolicyPrefillFilter filter = new InvocationPolicyPrefillFilter(lingRepository, registry);
        filter.doFilter(context, filterChain);

        // 关键断言：用 targetLingId（"demo-ling"）能查到策略并预填充
        assertEquals(5000, context.governance().getTimeoutMs());
    }
}
