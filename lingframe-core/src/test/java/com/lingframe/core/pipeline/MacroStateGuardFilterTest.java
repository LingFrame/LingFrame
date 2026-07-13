package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MacroStateGuardFilter 测试")
class MacroStateGuardFilterTest {

    @Mock
    private LingRepository lingRepository;

    @Mock
    private LingRuntime lingRuntime;

    @Mock
    private LingFilterChain filterChain;

    @InjectMocks
    private MacroStateGuardFilter filter;

    private InvocationContext context;

    @BeforeEach
    void setUp() {
        context = InvocationContext.obtain();
    }

    @AfterEach
    void tearDown() {
        context.reset();
    }

    @Nested
    @DisplayName("透传场景")
    class PassThroughTests {

        @Test
        @DisplayName("服务标识为空时应直接透传")
        void doFilter_WhenFqsidIsNull_ShouldPassThrough() throws Throwable {
            context.setServiceFQSID(null);
            Object expectedResult = new Object();
            when(filterChain.doFilter(context)).thenReturn(expectedResult);

            Object result = filter.doFilter(context, filterChain);

            assertEquals(expectedResult, result);
            verify(filterChain).doFilter(context);
            verifyNoInteractions(lingRepository);
        }

        @Test
        @DisplayName("运行时为 ACTIVE 时应直接透传")
        void doFilter_WhenStatusIsActive_ShouldPassThrough() throws Throwable {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRoutableTarget("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);

            Object expectedResult = new Object();
            when(filterChain.doFilter(context)).thenReturn(expectedResult);

            Object result = filter.doFilter(context, filterChain);

            assertEquals(expectedResult, result);
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("运行时为 DEGRADED 时应直接透传")
        void doFilter_WhenStatusIsDegraded_ShouldPassThrough() throws Throwable {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRoutableTarget("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.DEGRADED);

            Object expectedResult = new Object();
            when(filterChain.doFilter(context)).thenReturn(expectedResult);

            Object result = filter.doFilter(context, filterChain);

            assertEquals(expectedResult, result);
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("新格式 __provider__: FQSID 应优先读 targetLingId 而非占位符")
        void doFilter_WhenProviderFqsid_ShouldReadTargetLingIdNotPlaceholder() throws Throwable {
            // 模拟 ContractProviderRoutingFilter 在 L0 阶段已解析出真实 lingId
            context.setServiceFQSID("__provider__:com.example.UserService");
            context.setTargetLingId("ling-a");

            // 应该用 "ling-a" 查询，而不是 "__provider__" 占位符
            when(lingRepository.getRoutableTarget("ling-a")).thenReturn(lingRuntime);
            when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);

            Object expectedResult = new Object();
            when(filterChain.doFilter(context)).thenReturn(expectedResult);

            Object result = filter.doFilter(context, filterChain);

            assertEquals(expectedResult, result);
            // 关键断言：用真实 lingId 查询，绝不查占位符
            verify(lingRepository).getRoutableTarget("ling-a");
            verify(lingRepository, never()).getRoutableTarget("__provider__");
            verify(filterChain).doFilter(context);
        }
    }

    @Nested
    @DisplayName("拒绝场景")
    class RejectionTests {

        @Test
        @DisplayName("运行时不存在时应抛出路由失败")
        void doFilter_WhenRuntimeNotFound_ShouldThrowRouteFailure() {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRoutableTarget("demo-ling")).thenReturn(null);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("运行时为 INACTIVE 时应抛出路由失败")
        void doFilter_WhenStatusIsInactive_ShouldThrowRouteFailure() {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRoutableTarget("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.INACTIVE);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("运行时为 STOPPING 时应抛出状态拒绝")
        void doFilter_WhenStatusIsStopping_ShouldThrowStateRejected() {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRoutableTarget("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.STOPPING);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.STATE_REJECTED, ex.getKind());
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("运行时为 RECOVERING 时应抛出状态拒绝")
        void doFilter_WhenStatusIsRecovering_ShouldThrowStateRejected() {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRoutableTarget("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.RECOVERING);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.STATE_REJECTED, ex.getKind());
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("运行时为 REMOVED 时应抛出路由失败")
        void doFilter_WhenStatusIsRemoved_ShouldThrowRouteFailure() {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRoutableTarget("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.currentStatus()).thenReturn(RuntimeStatus.REMOVED);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
            verifyNoInteractions(filterChain);
        }
    }
}
