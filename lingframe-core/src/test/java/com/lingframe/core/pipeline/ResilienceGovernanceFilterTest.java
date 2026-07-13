package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResilienceGovernanceFilter 测试")
class ResilienceGovernanceFilterTest {

    @Mock
    private LingRepository lingRepository;

    @Mock
    private EventBus eventBus;

    @Mock
    private LingFilterChain filterChain;

    @Mock
    private LingRuntime lingRuntime;

    private ResilienceGovernanceFilter filter;
    private InvocationContext context;

    @BeforeEach
    void setUp() {
        filter = new ResilienceGovernanceFilter(lingRepository, eventBus);
        context = InvocationContext.obtain();
    }

    @AfterEach
    void tearDown() {
        context.reset();
        filter.evict("demo-ling");
    }

    @Nested
    @DisplayName("透传场景")
    class PassThroughTests {

        @Test
        @DisplayName("缺失服务标识时应直接透传")
        void doFilter_WhenNoFqsid_ShouldPassThrough() throws Throwable {
            context.setServiceFQSID(null);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertEquals(expected, result);
            verify(filterChain).doFilter(context);
            verifyNoInteractions(lingRepository);
        }

        @Test
        @DisplayName("限流阈值足够高时应正常透传")
        void doFilter_WhenConfigHasHighLimit_ShouldPassThrough() throws Throwable {
            setupMocks(100, 1000);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertEquals(expected, result);
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("新格式 __provider__: FQSID 应优先读 targetLingId 查限流器/熔断器")
        void doFilter_WhenProviderFqsid_ShouldReadTargetLingIdForLimiter() throws Throwable {
            // 模拟 ContractProviderRoutingFilter 已设置 targetLingId
            context.setServiceFQSID("__provider__:com.example.DemoService");
            context.setTargetLingId("demo-ling");

            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .bulkheadMaxConcurrent(100)
                    .defaultTimeoutMs(1000)
                    .build();
            when(lingRuntime.getConfig()).thenReturn(config);
            when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);

            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertEquals(expected, result);
            // 关键断言：熔断器创建在 "demo-ling" 名下，说明用了 targetLingId 而非占位符
            assertTrue(filter.hasBreaker("demo-ling"));
        }
    }

    @Nested
    @DisplayName("限流与熔断")
    class RateLimitAndCircuitBreakerTests {

        @Test
        @DisplayName("并发或速率超过限制时应抛出限流异常")
        void doFilter_WhenConcurrencyExceedsLimit_ShouldThrowRateLimited() throws Throwable {
            setupMocks(1, 1000);

            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result1 = filter.doFilter(context, filterChain);
            assertEquals(expected, result1);

            LingInvocationException rateLimitEx = null;
            for (int i = 0; i < 10; i++) {
                try {
                    filter.doFilter(context, filterChain);
                } catch (LingInvocationException e) {
                    if (e.getKind() == LingInvocationException.ErrorKind.RATE_LIMITED) {
                        rateLimitEx = e;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }

            assertNotNull(rateLimitEx, "Expected RATE_LIMITED exception but none was thrown");
        }

        @Test
        @DisplayName("连续错误超过阈值后应触发熔断")
        void doFilter_WhenErrorsExceedThreshold_ShouldTriggerCircuitBreaker() throws Throwable {
            setupMocks(100, 1000);

            RuntimeException businessEx = new RuntimeException("Business error");
            when(filterChain.doFilter(context)).thenThrow(businessEx);

            for (int i = 0; i < 10; i++) {
                RuntimeException ex = assertThrows(RuntimeException.class, () -> filter.doFilter(context, filterChain));
                assertEquals("Business error", ex.getMessage());
            }

            LingInvocationException cbEx = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.CIRCUIT_OPEN, cbEx.getKind());
            verify(filterChain, times(10)).doFilter(context);
        }

        @Test
        @DisplayName("治理限流阈值变化后应切换到新的限流器配置")
        void doFilter_WhenGovernedRateLimitChanges_ShouldRefreshLimiter() throws Throwable {
            setupMocks(100, 1000);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            context.governance().setRateLimitPerSecond(100);
            assertEquals(expected, filter.doFilter(context, filterChain));

            context.governance().setRateLimitPerSecond(1);
            assertEquals(expected, filter.doFilter(context, filterChain));

            LingInvocationException rateLimitEx = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));
            assertEquals(LingInvocationException.ErrorKind.RATE_LIMITED, rateLimitEx.getKind());
        }

        @Test
        @DisplayName("治理 timeout 变化后应重建熔断器（新 breaker 为 CLOSED）")
        void doFilter_WhenGovernedTimeoutChanges_ShouldRebuildBreaker() throws Throwable {
            setupMocks(100, 1000);
            RuntimeException businessEx = new RuntimeException("Business error");
            when(filterChain.doFilter(context)).thenThrow(businessEx);

            // 第一次：governedTimeout=1000，触发 10 次错误让 breaker OPEN
            context.governance().setTimeoutMs(1000);
            for (int i = 0; i < 10; i++) {
                assertThrows(RuntimeException.class, () -> filter.doFilter(context, filterChain));
            }
            // breaker 已 OPEN，第 11 次请求被 CIRCUIT_OPEN 拒绝
            LingInvocationException cbEx = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));
            assertEquals(LingInvocationException.ErrorKind.CIRCUIT_OPEN, cbEx.getKind());

            // 第二次：governedTimeout 变为 5000，breaker 应重建（新 breaker 为 CLOSED）
            context.governance().setTimeoutMs(5000);
            // 重建后 breaker 是 CLOSED，tryAcquirePermission 返回 true，请求进入 chain（抛 businessEx）
            RuntimeException ex = assertThrows(RuntimeException.class, () -> filter.doFilter(context, filterChain));
            assertEquals("Business error", ex.getMessage());
        }

        @Test
        @DisplayName("ctx.governance().getTimeoutMs() 为 null 时回退 config 默认值构建熔断器")
        void doFilter_WhenGovernedTimeoutNull_ShouldFallBackToConfig() throws Throwable {
            setupMocks(100, 1000);
            // 不设置 ctx.governance().setTimeoutMs（为 null），getBreaker 应回退 config.getDefaultTimeoutMs()
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertEquals(expected, result);
            assertTrue(filter.hasBreaker("demo-ling"));
        }

        @Test
        @DisplayName("卸载驱逐后应同步清理熔断与限流状态")
        void doFilter_WhenEvicted_ShouldReleaseBreakerAndLimiterState() throws Throwable {
            setupMocks(10, 1000);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            assertEquals(expected, filter.doFilter(context, filterChain));
            assertTrue(filter.hasLimiter("demo-ling"));
            assertTrue(filter.hasBreaker("demo-ling"));

            filter.evict("demo-ling");

            assertFalse(filter.hasLimiter("demo-ling"));
            assertFalse(filter.hasBreaker("demo-ling"));
        }

        @Test
        @DisplayName("恢复治理状态时应只重置熔断器")
        void recover_ShouldOnlyClearBreakerState() throws Throwable {
            setupMocks(10, 1000);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            assertEquals(expected, filter.doFilter(context, filterChain));
            assertTrue(filter.hasLimiter("demo-ling"));
            assertTrue(filter.hasBreaker("demo-ling"));

            assertTrue(filter.recover("demo-ling"));
            assertTrue(filter.hasLimiter("demo-ling"));
            assertFalse(filter.hasBreaker("demo-ling"));
        }
    }

    private void setupMocks(int maxConcurrent, int defaultTimeoutMs) {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        LingRuntimeConfig config = LingRuntimeConfig.builder()
                .bulkheadMaxConcurrent(maxConcurrent)
                .defaultTimeoutMs(defaultTimeoutMs)
                .build();

        when(lingRuntime.getConfig()).thenReturn(config);
        lenient().when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
    }
}
