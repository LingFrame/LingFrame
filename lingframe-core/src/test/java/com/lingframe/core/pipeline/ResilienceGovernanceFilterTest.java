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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
