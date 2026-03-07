package com.lingframe.core.pipeline;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
        // 创建真实的 Filter 实例
        filter = new ResilienceGovernanceFilter(lingRepository, eventBus);
        context = InvocationContext.obtain();
    }

    @AfterEach
    void tearDown() {
        context.reset();
        // 清理缓存
        filter.evict("demo-ling");
    }

    @Test
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
    void doFilter_WhenConfigHasHighLimit_ShouldPassThrough() throws Throwable {
        setupMocks(100, 1000); // 100 QPS, 1000ms timeout
        Object expected = new Object();
        when(filterChain.doFilter(context)).thenReturn(expected);

        Object result = filter.doFilter(context, filterChain);

        assertEquals(expected, result);
        verify(filterChain).doFilter(context);
    }

    @Test
    void doFilter_WhenConcurrencyExceedsLimit_ShouldThrowRateLimited() throws Throwable {
        // 限流设置为 1，代表超过 1 个并发或 QPS 会触发限流拦截
        setupMocks(1, 1000);

        // 第一个请求模拟慢调用，使其占据锁或消耗令牌（虽然 TokenBucket 是基于时间生成的，但不为 0 就能获取 1 个）
        Object expected = new Object();
        when(filterChain.doFilter(context)).thenReturn(expected);

        // 第一个顺利通过
        Object result1 = filter.doFilter(context, filterChain);
        assertEquals(expected, result1);

        // 如果我们在同一毫秒内突发第二个请求，由于 capacity 是 1，且填充速率也是 1，可能会被拒
        // 为了确保限流能被触发，我们可以循环调用多次
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
    void doFilter_WhenErrorsExceedThreshold_ShouldTriggerCircuitBreaker() throws Throwable {
        // 滑动窗口：最小调用数 10，失败率 50%
        setupMocks(100, 1000);

        // 模拟业务抛出异常
        RuntimeException businessEx = new RuntimeException("Business error");
        when(filterChain.doFilter(context)).thenThrow(businessEx);

        // 触发熔断：我们连续调用 10 次，达到最小统计数，全错（100% 失败 > 50%）
        for (int i = 0; i < 10; i++) {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> {
                filter.doFilter(context, filterChain);
            });
            assertEquals("Business error", ex.getMessage());
        }

        // 第 11 次应该触发熔断（直接报错 CIRCUIT_OPEN，不再执行 filterChain）
        LingInvocationException cbEx = assertThrows(LingInvocationException.class, () -> {
            filter.doFilter(context, filterChain);
        });

        assertEquals(LingInvocationException.ErrorKind.CIRCUIT_OPEN, cbEx.getKind());
        // 验证只调用了 10 次（第 11 次被熔断器直接拦截）
        verify(filterChain, times(10)).doFilter(context);
    }

    private void setupMocks(int maxConcurrent, int defaultTimeoutMs) {
        context.setServiceFQSID("demo-ling:com.example.DemoService");
        LingRuntimeConfig config = LingRuntimeConfig.builder()
                .bulkheadMaxConcurrent(maxConcurrent)
                .defaultTimeoutMs(defaultTimeoutMs)
                .build();

        when(lingRuntime.getConfig()).thenReturn(config);

        // 防止严格模式的 mockito 报错 UnnecessaryStubbingException
        lenient().when(lingRepository.getRuntime("demo-ling")).thenReturn(lingRuntime);
    }
}
