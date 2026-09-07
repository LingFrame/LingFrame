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

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        @DisplayName("裸 contractId FQSID 应优先读 targetLingId 查限流器/熔断器")
        void doFilter_WhenBareContractId_ShouldReadTargetLingIdForLimiter() throws Throwable {
            // 模拟 ContractProviderRoutingFilter 已设置 targetLingId，FQSID 保持裸 contractId
            context.setServiceFQSID("com.example.DemoService");
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
        @DisplayName("SIMULATION 干跑不消费限流预算：即使限流阈值极低也应透传")
        void doFilter_WhenSimulationMode_ShouldBypassRateLimiter() throws Throwable {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            context.execution().setMode(InvocationExecutionMode.SIMULATION);

            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            for (int i = 0; i < 5; i++) {
                Object result = filter.doFilter(context, filterChain);
                assertEquals(expected, result);
            }
            // 模拟流量不应创建限流器/熔断器，避免污染真实弹性状态
            assertFalse(filter.hasLimiter("demo-ling"));
            assertFalse(filter.hasBreaker("demo-ling"));
        }

        @Test
        @DisplayName("SIMULATION 干跑失败不应记入熔断器（模拟探针不污染真实故障统计）")
        void doFilter_WhenSimulationMode_ShouldNotPolluteCircuitBreaker() throws Throwable {
            context.setServiceFQSID("demo-ling:com.example.DemoService");
            context.execution().setMode(InvocationExecutionMode.SIMULATION);

            when(filterChain.doFilter(context)).thenThrow(new IllegalStateException("simulated failure"));

            for (int i = 0; i < 10; i++) {
                try {
                    filter.doFilter(context, filterChain);
                } catch (Throwable ignored) {
                    // 模拟失败透传，但不应触发熔断统计
                }
            }
            assertFalse(filter.hasBreaker("demo-ling"), "SIMULATION 失败不应创建/污染熔断器");
        }

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

        @Test
        @DisplayName("reportOutcome 喂失败累积到阈值后应使熔断器 OPEN（补 GOVERN_ONLY 回灌缺口）")
        void reportOutcome_WhenFailuresReachThreshold_ShouldOpenBreaker() throws Throwable {
            setupMocks(100, 1000);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            // 先经 doFilter 创建熔断器（正常走一次）
            assertEquals(expected, filter.doFilter(context, filterChain));
            assertTrue(filter.hasBreaker("demo-ling"));

            // 回灌失败：达到 minimumCalls 且失败率超阈值后熔断器应 OPEN
            Exception fault = new IOException("downstream unavailable");
            for (int i = 0; i < 10; i++) {
                filter.reportOutcome("demo-ling", false, 1_000_000L, fault);
            }

            // 熔断器已 OPEN，下次 doFilter 应被 CIRCUIT_OPEN 拒绝
            LingInvocationException cbEx = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));
            assertEquals(LingInvocationException.ErrorKind.CIRCUIT_OPEN, cbEx.getKind());
        }

        @Test
        @DisplayName("下游治理拒绝（BULKHEAD_FULL）不得计入熔断失败率（倒挂修复回归）")
        void doFilter_WhenDownstreamGovernanceRejection_ShouldNotOpenBreaker() throws Throwable {
            setupMocks(100, 1000);

            // 模拟隔离阶段（ThreadIsolationGovernanceFilter 舱满）抛出的治理拒绝
            LingInvocationException bulkheadEx = new LingInvocationException(
                    "demo-ling:com.example.DemoService", LingInvocationException.ErrorKind.BULKHEAD_FULL);
            when(filterChain.doFilter(context)).thenThrow(bulkheadEx);

            for (int i = 0; i < 10; i++) {
                LingInvocationException ex = assertThrows(LingInvocationException.class,
                        () -> filter.doFilter(context, filterChain));
                assertEquals(LingInvocationException.ErrorKind.BULKHEAD_FULL, ex.getKind());
            }

            // 10 次治理拒绝后熔断器不应 OPEN：第 11 次仍应进入 chain（仍抛 BULKHEAD_FULL，
            // 而非被 CIRCUIT_OPEN 拒绝）——若倒挂未修复，此处会误抛 CIRCUIT_OPEN 使断言失败
            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));
            assertEquals(LingInvocationException.ErrorKind.BULKHEAD_FULL, ex.getKind());
            verify(filterChain, times(11)).doFilter(context);
        }

        @Test
        @DisplayName("下游真实故障（INVOKE_ERROR）仍应计入熔断失败率（倒挂修复不误伤）")
        void doFilter_WhenDownstreamInvokeError_ShouldOpenBreaker() throws Throwable {
            setupMocks(100, 1000);

            // 业务执行报错（真实下游故障，governanceRejection=false）必须继续喂熔断器
            LingInvocationException invokeError = new LingInvocationException(
                    "demo-ling:com.example.DemoService", LingInvocationException.ErrorKind.INVOKE_ERROR);
            when(filterChain.doFilter(context)).thenThrow(invokeError);

            for (int i = 0; i < 10; i++) {
                LingInvocationException ex = assertThrows(LingInvocationException.class,
                        () -> filter.doFilter(context, filterChain));
                assertEquals(LingInvocationException.ErrorKind.INVOKE_ERROR, ex.getKind());
            }

            // 10 次真实故障后熔断器应 OPEN：第 11 次被 CIRCUIT_OPEN 拒绝
            LingInvocationException cbEx = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));
            assertEquals(LingInvocationException.ErrorKind.CIRCUIT_OPEN, cbEx.getKind());
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
