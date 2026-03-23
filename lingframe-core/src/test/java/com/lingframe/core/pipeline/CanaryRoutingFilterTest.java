package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.TrafficRouter;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CanaryRoutingFilter 测试")
class CanaryRoutingFilterTest {

    @Mock
    private LingRepository lingRepository;

    @Mock
    private TrafficRouter trafficRouter;

    @Mock
    private LingFilterChain filterChain;

    @Mock
    private LingRuntime lingRuntime;

    @Mock
    private LingInstance targetInstance;

    @InjectMocks
    private CanaryRoutingFilter filter;

    private InvocationContext context;

    @BeforeEach
    void setUp() {
        context = InvocationContext.obtain();
    }

    @AfterEach
    void tearDown() {
        context.recycle();
    }

    @Nested
    @DisplayName("透传场景")
    class PassThroughTests {

        @Test
        @DisplayName("目标实例已解析时应直接透传")
        void shouldPassThroughWhenTargetAlreadyResolved() throws Throwable {
            Object expected = new Object();
            context.routing().setTargetInstance(targetInstance);
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            verify(filterChain).doFilter(context);
            verifyNoInteractions(lingRepository);
            verifyNoInteractions(trafficRouter);
        }

        @Test
        @DisplayName("缺失服务标识时应直接透传")
        void shouldPassThroughWhenServiceIdIsMissing() throws Throwable {
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("治理专用模式下缺失运行时时应直接透传")
        void shouldPassThroughInGovernOnlyModeWhenRuntimeIsMissing() throws Throwable {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            context.setExecutionMode(InvocationExecutionMode.GOVERN_ONLY);
            Object expected = new Object();
            when(lingRepository.getRuntime("demo-ling")).thenReturn(null);
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            verify(filterChain).doFilter(context);
        }
    }

    @Nested
    @DisplayName("失败场景")
    class FailureTests {

        @Test
        @DisplayName("运行时缺失时应抛出路由失败")
        void shouldThrowRouteFailureWhenRuntimeIsMissing() {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRuntime("demo-ling")).thenReturn(null);

            LingInvocationException exception = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, exception.getKind());
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("没有就绪实例时应抛出路由失败")
        void shouldThrowRouteFailureWhenNoReadyInstancesExist() {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.getReadyInstances()).thenReturn(Collections.emptyList());

            LingInvocationException exception = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, exception.getKind());
        }

        @Test
        @DisplayName("路由器返回空结果时应抛出路由失败")
        void shouldThrowRouteFailureWhenRouterReturnsNull() {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.getReadyInstances()).thenReturn(Collections.singletonList(targetInstance));
            when(trafficRouter.route(any(), eq(context))).thenReturn(null);

            LingInvocationException exception = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, exception.getKind());
        }
    }

    @Nested
    @DisplayName("路由成功")
    class RouteSuccessTests {

        @Test
        @DisplayName("路由成功时应写回路由状态")
        void shouldWriteRoutingStateWhenRouteSucceeds() throws Throwable {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
            when(lingRuntime.getReadyInstances()).thenReturn(Arrays.asList(targetInstance));
            when(trafficRouter.route(any(), eq(context))).thenReturn(targetInstance);
            when(targetInstance.getLingId()).thenReturn("demo-ling");
            when(targetInstance.getVersion()).thenReturn("1.0.0");
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertEquals("demo-ling", context.getTargetLingId());
            assertEquals("1.0.0", context.getTargetVersion());
            assertSame(targetInstance, context.routing().getTargetInstance());
        }
    }
}
