package com.lingframe.core.pipeline;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.LingHealthMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TrafficMetricsFilter 测试。
 * 覆盖：traceId 同步、指标记录、深度管理、运行时请求计数。
 */
@DisplayName("TrafficMetricsFilter 测试")
class TrafficMetricsFilterTest {

    @BeforeEach
    @AfterEach
    void cleanCallContext() {
        LingCallContext.clear();
    }

    // ==================== traceId 同步 ====================

    @Nested
    @DisplayName("traceId 同步")
    class TraceIdSync {

        @Test
        @DisplayName("ctx 无 traceId 时自动生成并同步到 ThreadLocal")
        void autoGenerateTraceId() throws Throwable {
            TrafficMetricsFilter filter = new TrafficMetricsFilter();
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            filter.doFilter(ctx, (c) -> {
                // 执行期间 ThreadLocal 应有 traceId
                assertNotNull(LingCallContext.getTraceId());
                assertEquals(LingCallContext.getTraceId(), c.getTraceId());
                return "ok";
            });

            assertNotNull(ctx.getTraceId());
        }

        @Test
        @DisplayName("ctx 已有 traceId 时同步到 ThreadLocal")
        void existingTraceIdSynced() throws Throwable {
            TrafficMetricsFilter filter = new TrafficMetricsFilter();
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");
            ctx.setTraceId("custom-trace-123");

            filter.doFilter(ctx, (c) -> {
                assertEquals("custom-trace-123", LingCallContext.getTraceId());
                return "ok";
            });
        }
    }

    // ==================== 深度管理 ====================

    @Nested
    @DisplayName("调用深度管理")
    class DepthManagement {

        @Test
        @DisplayName("嵌套调用深度递增递减")
        void nestedCallDepth() throws Throwable {
            TrafficMetricsFilter filter = new TrafficMetricsFilter();

            InvocationContext ctx1 = InvocationContext.obtain();
            ctx1.setServiceFQSID("ling-1:Service");

            filter.doFilter(ctx1, (c1) -> {
                assertEquals(1, LingCallContext.getDepth());

                InvocationContext ctx2 = InvocationContext.obtain();
                ctx2.setServiceFQSID("ling-1:Service");

                filter.doFilter(ctx2, (c2) -> {
                    assertEquals(2, LingCallContext.getDepth());
                    return "inner";
                });

                assertEquals(1, LingCallContext.getDepth());
                return "outer";
            });

            assertEquals(0, LingCallContext.getDepth());
        }

        @Test
        @DisplayName("异常时深度也正确递减")
        void depthDecrementedOnException() throws Throwable {
            TrafficMetricsFilter filter = new TrafficMetricsFilter();
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            try {
                filter.doFilter(ctx, (c) -> {
                    throw new RuntimeException("test");
                });
            } catch (RuntimeException ignored) {
            }

            assertEquals(0, LingCallContext.getDepth());
        }
    }

    // ==================== 运行时请求计数 ====================

    @Nested
    @DisplayName("运行时请求计数")
    class RuntimeRequestCount {

        @Test
        @DisplayName("成功调用 startRequest/endRequest 配对")
        void startEndRequestPaired() throws Throwable {
            LingRuntime runtime = mock(LingRuntime.class);
            LingRepository repo = mock(LingRepository.class);
            when(repo.getRuntime("ling-1")).thenReturn(runtime);

            TrafficMetricsFilter filter = new TrafficMetricsFilter(repo);
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            filter.doFilter(ctx, (c) -> "ok");

            verify(runtime).startRequest();
            verify(runtime).endRequest();
        }

        @Test
        @DisplayName("异常调用也 endRequest")
        void endRequestOnException() throws Throwable {
            LingRuntime runtime = mock(LingRuntime.class);
            LingRepository repo = mock(LingRepository.class);
            when(repo.getRuntime("ling-1")).thenReturn(runtime);

            TrafficMetricsFilter filter = new TrafficMetricsFilter(repo);
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            try {
                filter.doFilter(ctx, (c) -> {
                    throw new RuntimeException("test");
                });
            } catch (RuntimeException ignored) {
            }

            verify(runtime).startRequest();
            verify(runtime).endRequest();
        }

        @Test
        @DisplayName("无 repository 时不抛异常")
        void noRepositorySafe() throws Throwable {
            TrafficMetricsFilter filter = new TrafficMetricsFilter();
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            assertDoesNotThrow(() -> filter.doFilter(ctx, (c) -> "ok"));
        }
    }

    // ==================== 指标记录 ====================

    @Nested
    @DisplayName("指标记录")
    class MetricsRecording {

        @Test
        @DisplayName("成功调用记录指标")
        void successRecordsMetrics() throws Throwable {
            MetricsCollector collector = mock(MetricsCollector.class);
            LingHealthMetrics healthMetrics = mock(LingHealthMetrics.class);
            when(collector.getOrCreate(anyString())).thenReturn(healthMetrics);
            when(collector.getOrCreate(anyString(), any())).thenReturn(healthMetrics);

            TrafficMetricsFilter filter = new TrafficMetricsFilter(null, collector);

            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            filter.doFilter(ctx, (c) -> "ok");

            verify(collector, atLeastOnce()).getOrCreate("ling-1");
            verify(healthMetrics, atLeastOnce()).recordSuccess(anyLong());
        }

        @Test
        @DisplayName("异常调用记录失败指标")
        void failureRecordsMetrics() throws Throwable {
            MetricsCollector collector = mock(MetricsCollector.class);
            LingHealthMetrics healthMetrics = mock(LingHealthMetrics.class);
            when(collector.getOrCreate(anyString())).thenReturn(healthMetrics);
            when(collector.getOrCreate(anyString(), any())).thenReturn(healthMetrics);

            TrafficMetricsFilter filter = new TrafficMetricsFilter(null, collector);

            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            try {
                filter.doFilter(ctx, (c) -> {
                    throw new RuntimeException("test");
                });
            } catch (RuntimeException ignored) {
            }

            verify(collector, atLeastOnce()).getOrCreate("ling-1");
            verify(healthMetrics, atLeastOnce()).recordFailure(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("无 MetricsCollector 时不抛异常")
        void noCollectorSafe() throws Throwable {
            TrafficMetricsFilter filter = new TrafficMetricsFilter((LingRepository) null);
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            assertDoesNotThrow(() -> filter.doFilter(ctx, (c) -> "ok"));
        }
    }

    // ==================== Order ====================

    @Test
    @DisplayName("过滤器顺序为 METRICS 阶段")
    void orderIsMetrics() {
        TrafficMetricsFilter filter = new TrafficMetricsFilter();
        assertEquals(FilterPhase.METRICS, filter.getOrder());
    }
}
