package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.routing.ContractProviderRoutingFilter;
import com.lingframe.core.routing.ProviderDescriptor;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.RoutableTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ContractProviderRoutingFilter 测试。
 * <p>
 * 去身份化后触发条件为 ctx.getTargetLingId() == null，
 * 候选 provider 默认按注册时携带的 weight 决策，方法级资格过滤决定谁进池子。
 * 路由层不引用 ProviderKind，不再使用 __provider__: 前缀。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContractProviderRoutingFilter 测试")
class ContractProviderRoutingFilterTest {

    @Mock
    private LingServiceRegistry lingServiceRegistry;

    @Mock
    private LingRepository lingRepository;

    @Mock
    private LingFilterChain filterChain;

    @Mock
    private RoutableTarget routableTarget;

    private InvocationContext context;

    private ContractProviderRoutingFilter filter;

    @BeforeEach
    void setUp() {
        // 用真实 ProviderWeightRouter 替代 mock，让权重选择逻辑可测
        filter = new ContractProviderRoutingFilter(lingServiceRegistry, lingRepository, new ProviderWeightRouter());
        context = InvocationContext.obtain();
    }

    @AfterEach
    void tearDown() {
        context.recycle();
    }

    // ==================== 顺序契约 ====================

    @Test
    @DisplayName("过滤器顺序为 PROVIDER_ROUTING 阶段（-100）")
    void orderIsProviderRouting() {
        assertEquals(FilterPhase.PROVIDER_ROUTING, filter.getOrder());
        assertEquals(-100, filter.getOrder());
    }

    // ==================== 入口已锁定灵元时放行 ====================

    @Nested
    @DisplayName("入口已锁定目标灵元")
    class TargetLingIdPreset {

        @Test
        @DisplayName("targetLingId 非空时应直接透传，不触发 L0 路由")
        void targetLingIdPresetPassThrough() throws Throwable {
            context.setServiceFQSID("com.example.UserService");
            context.setTargetLingId("ling-a");
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            verify(filterChain).doFilter(context);
            verifyNoInteractions(lingServiceRegistry, lingRepository);
        }

        @Test
        @DisplayName("FQSID 为 null 时应直接透传")
        void nullFqsidPassThrough() throws Throwable {
            context.setServiceFQSID(null);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            verifyNoInteractions(lingServiceRegistry, lingRepository);
        }
    }

    // ==================== L0 provider 路由（裸 contractId） ====================

    @Nested
    @DisplayName("裸 contractId FQSID 路由")
    class ProviderRouting {

        @Test
        @DisplayName("单 provider 时选中并设置 runtime + targetLingId")
        void singleProviderSelected() throws Throwable {
            // 裸 contractId 作为 FQSID
            context.setServiceFQSID("com.example.UserService");
            context.setMethodName("getUser");
            context.setParameterTypeNames(new String[]{"java.lang.String"});
            ProviderDescriptor core = new ProviderDescriptor(
                    "com.example.UserService", "ling-core", 100);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Collections.singletonList(core));
            // 方法级资格过滤：灵核声明了 getUser(String)
            when(lingServiceRegistry.hasMethod("ling-core:com.example.UserService", "getUser",
                    new String[]{"java.lang.String"})).thenReturn(true);
            when(lingRepository.getRoutableTarget("ling-core")).thenReturn(routableTarget);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertEquals("ling-core", context.getTargetLingId());
            assertSame(routableTarget, context.getRuntime());
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("多 provider 无 Dashboard 配置时按注册 weight 决策")
        void multipleProvidersWeightDecision() throws Throwable {
            context.setServiceFQSID("com.example.UserService");
            context.setMethodName("getUser");
            context.setParameterTypeNames(new String[]{"java.lang.String"});
            // 灵核 weight=100，灵元 weight=0（默认注册策略沉淀的身份影响）
            ProviderDescriptor core = new ProviderDescriptor(
                    "com.example.UserService", "ling-core", 100);
            ProviderDescriptor ling = new ProviderDescriptor(
                    "com.example.UserService", "ling-a", 0);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Arrays.asList(core, ling));
            // 两者都声明了该方法，进池子
            when(lingServiceRegistry.hasMethod("ling-core:com.example.UserService", "getUser",
                    new String[]{"java.lang.String"})).thenReturn(true);
            when(lingServiceRegistry.hasMethod("ling-a:com.example.UserService", "getUser",
                    new String[]{"java.lang.String"})).thenReturn(true);
            when(lingRepository.getRoutableTarget("ling-core")).thenReturn(routableTarget);
            when(filterChain.doFilter(context)).thenReturn(null);

            filter.doFilter(context, filterChain);

            // 默认 weight=100 > 0，灵核承接全量
            assertEquals("ling-core", context.getTargetLingId());
            assertSame(routableTarget, context.getRuntime());
        }

        @Test
        @DisplayName("新灵元未实现该方法时流量落回灵核（方法级 fallback）")
        void methodFallbackToCore() throws Throwable {
            context.setServiceFQSID("com.example.UserService");
            context.setMethodName("updateUser");
            context.setParameterTypeNames(new String[]{"java.lang.String"});
            // 灵核 weight=100，灵元 weight=50（Dashboard 已下发但未覆盖权重）
            ProviderDescriptor core = new ProviderDescriptor(
                    "com.example.UserService", "ling-core", 100);
            ProviderDescriptor ling = new ProviderDescriptor(
                    "com.example.UserService", "ling-a", 50);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Arrays.asList(core, ling));
            // 灵核声明了 updateUser(String)，新灵元没声明 → 被剔除
            when(lingServiceRegistry.hasMethod("ling-core:com.example.UserService", "updateUser",
                    new String[]{"java.lang.String"})).thenReturn(true);
            when(lingServiceRegistry.hasMethod("ling-a:com.example.UserService", "updateUser",
                    new String[]{"java.lang.String"})).thenReturn(false);
            when(lingRepository.getRoutableTarget("ling-core")).thenReturn(routableTarget);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            // 命中灵核，不命中灵元——方法级 fallback 成立
            assertEquals("ling-core", context.getTargetLingId());
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("provider 列表为空时应容错放行")
        void emptyProvidersPassThrough() throws Throwable {
            context.setServiceFQSID("unknown.Contract");
            context.setMethodName("anyMethod");
            context.setParameterTypeNames(new String[0]);
            when(lingServiceRegistry.getProvidersByContractId("unknown.Contract"))
                    .thenReturn(Collections.emptyList());
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertNull(context.getRuntime(), "无 provider 选中时 runtime 应保持 null");
            verifyNoInteractions(lingRepository);
        }

        @Test
        @DisplayName("选中的 provider 在 repository 中不存在时应抛路由失败")
        void runtimeNotFoundThrowsRouteFailure() {
            context.setServiceFQSID("com.example.UserService");
            context.setMethodName("getUser");
            context.setParameterTypeNames(new String[]{"java.lang.String"});
            ProviderDescriptor core = new ProviderDescriptor(
                    "com.example.UserService", "ling-core", 100);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Collections.singletonList(core));
            when(lingServiceRegistry.hasMethod("ling-core:com.example.UserService", "getUser",
                    new String[]{"java.lang.String"})).thenReturn(true);
            when(lingRepository.getRoutableTarget("ling-core")).thenReturn(null);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("灵核没有基线实现时路由直接选灵元（干净核心场景）")
        void noCoreBaseline_routeToLing() throws Throwable {
            context.setServiceFQSID("com.example.UserService");
            context.setMethodName("getUser");
            context.setParameterTypeNames(new String[]{"java.lang.String"});
            // 候选池只有灵元 provider，没有灵核 provider
            ProviderDescriptor ling = new ProviderDescriptor(
                    "com.example.UserService", "ling-a", 100);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Collections.singletonList(ling));
            when(lingServiceRegistry.hasMethod("ling-a:com.example.UserService", "getUser",
                    new String[]{"java.lang.String"})).thenReturn(true);
            when(lingRepository.getRoutableTarget("ling-a")).thenReturn(routableTarget);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertEquals("ling-a", context.getTargetLingId(),
                    "灵核没有基线实现时应直接路由到灵元，而非误判为配置缺失");
            verify(filterChain).doFilter(context);
        }
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("serviceRegistry 为 null 时应容错放行")
    void nullServiceRegistryPassThrough() throws Throwable {
        ContractProviderRoutingFilter nullRegistryFilter = new ContractProviderRoutingFilter(
                null, lingRepository, new ProviderWeightRouter());
        context.setServiceFQSID("com.example.UserService");
        Object expected = new Object();
        when(filterChain.doFilter(context)).thenReturn(expected);

        Object result = nullRegistryFilter.doFilter(context, filterChain);

        assertSame(expected, result);
        verifyNoInteractions(lingRepository);
    }
}
