package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.ProviderDescriptor;
import com.lingframe.core.ling.ProviderKind;
import com.lingframe.core.router.ProviderWeightRouter;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.RoutableTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ContractProviderRoutingFilter 测试。
 * 覆盖：旧格式兼容、新格式 provider 路由、容错放行、runtime 设置、providerKind 埋点。
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

    @InjectMocks
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

    // ==================== 旧格式兼容 ====================

    @Nested
    @DisplayName("旧格式 FQSID 兼容")
    class OldFqsidCompat {

        @Test
        @DisplayName("旧格式 FQSID 应直接透传，不触发 provider 路由")
        void oldFqsidPassThrough() throws Throwable {
            context.setServiceFQSID("ling-a:com.example.UserService");
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

    // ==================== 新格式 provider 路由 ====================

    @Nested
    @DisplayName("新格式 __provider__: FQSID 路由")
    class ProviderRouting {

        @Test
        @DisplayName("单 provider 时选中并设置 runtime + targetLingId + providerKind")
        void singleProviderSelected() throws Throwable {
            context.setServiceFQSID("__provider__:com.example.UserService");
            ProviderDescriptor core = new ProviderDescriptor(
                    "com.example.UserService", "ling-core", ProviderKind.CORE, 100);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Collections.singletonList(core));
            when(lingRepository.getRoutableTarget("ling-core")).thenReturn(routableTarget);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertEquals("ling-core", context.getTargetLingId());
            assertSame(routableTarget, context.getRuntime());
            assertSame(ProviderKind.CORE, context.routing().getProviderKind());
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("多 provider 无 Dashboard 配置时灵核承接全量")
        void multipleProvidersCoreWins() throws Throwable {
            context.setServiceFQSID("__provider__:com.example.UserService");
            ProviderDescriptor core = new ProviderDescriptor(
                    "com.example.UserService", "ling-core", ProviderKind.CORE, 100);
            ProviderDescriptor ling = new ProviderDescriptor(
                    "com.example.UserService", "ling-a", ProviderKind.LING, 100);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Arrays.asList(core, ling));
            when(lingRepository.getRoutableTarget("ling-core")).thenReturn(routableTarget);
            when(filterChain.doFilter(context)).thenReturn(null);

            filter.doFilter(context, filterChain);

            assertEquals("ling-core", context.getTargetLingId());
            assertSame(routableTarget, context.getRuntime());
            assertSame(ProviderKind.CORE, context.routing().getProviderKind());
        }

        @Test
        @DisplayName("provider 列表为空时应容错放行")
        void emptyProvidersPassThrough() throws Throwable {
            context.setServiceFQSID("__provider__:unknown.Contract");
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
            context.setServiceFQSID("__provider__:com.example.UserService");
            ProviderDescriptor core = new ProviderDescriptor(
                    "com.example.UserService", "ling-core", ProviderKind.CORE, 100);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Collections.singletonList(core));
            when(lingRepository.getRoutableTarget("ling-core")).thenReturn(null);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("灵核没有基线实现时路由直接选灵元（干净核心场景）")
        void noCoreBaseline_routeToLing() throws Throwable {
            context.setServiceFQSID("__provider__:com.example.UserService");
            // 候选池只有 LING provider，没有 CORE provider
            ProviderDescriptor ling = new ProviderDescriptor(
                    "com.example.UserService", "ling-a", ProviderKind.LING, 100);
            when(lingServiceRegistry.getProvidersByContractId("com.example.UserService"))
                    .thenReturn(Collections.singletonList(ling));
            when(lingRepository.getRoutableTarget("ling-a")).thenReturn(routableTarget);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertEquals("ling-a", context.getTargetLingId(),
                    "灵核没有基线实现时应直接路由到灵元，而非误判为配置缺失");
            assertSame(ProviderKind.LING, context.routing().getProviderKind());
            verify(filterChain).doFilter(context);
        }
    }

    // ==================== 容错 ====================

    @Test
    @DisplayName("serviceRegistry 为 null 时应容错放行")
    void nullServiceRegistryPassThrough() throws Throwable {
        ContractProviderRoutingFilter nullRegistryFilter = new ContractProviderRoutingFilter(
                null, lingRepository, new ProviderWeightRouter());
        context.setServiceFQSID("__provider__:com.example.UserService");
        Object expected = new Object();
        when(filterChain.doFilter(context)).thenReturn(expected);

        Object result = nullRegistryFilter.doFilter(context, filterChain);

        assertSame(expected, result);
        verifyNoInteractions(lingRepository);
    }
}
