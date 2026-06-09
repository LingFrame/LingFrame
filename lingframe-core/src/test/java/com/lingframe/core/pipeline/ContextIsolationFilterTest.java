package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ContextIsolationFilter 测试。
 * 覆盖：TCCL 切换、模拟/穿刺模式跳过、无目标实例异常、ClassLoader 异常。
 */
@DisplayName("ContextIsolationFilter 测试")
class ContextIsolationFilterTest {

    private ContextIsolationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ContextIsolationFilter();
    }

    private InvocationContext createContextWithTarget(LingInstance target) {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling-1:com.example.Service");

        // 构造 routing state 并设置目标实例
        InvocationRoutingState routing = ctx.routing();
        if (target != null) {
            routing.setTargetInstance(target);
        }
        return ctx;
    }

    private LingInstance mockInstance(ClassLoader classLoader) {
        LingInstance instance = mock(LingInstance.class);
        when(instance.getClassLoader()).thenReturn(classLoader);
        return instance;
    }

    // ==================== TCCL 切换 ====================

    @Nested
    @DisplayName("TCCL 切换")
    class TcclSwitch {

        @Test
        @DisplayName("执行期间 TCCL 切换到目标 ClassLoader")
        void tcclSwitchedDuringExecution() throws Throwable {
            ClassLoader targetCl = mock(ClassLoader.class);
            LingInstance instance = mockInstance(targetCl);
            InvocationContext ctx = createContextWithTarget(instance);

            ClassLoader originalCl = Thread.currentThread().getContextClassLoader();

            LingInvocationFilter verifier = (c, chain) -> {
                assertSame(targetCl, Thread.currentThread().getContextClassLoader());
                return "ok";
            };

            filter.doFilter(ctx, (c) -> verifier.doFilter(c, mock(LingFilterChain.class)));

            // 执行完毕后恢复原始 TCCL
            assertSame(originalCl, Thread.currentThread().getContextClassLoader());
        }

        @Test
        @DisplayName("异常时也恢复原始 TCCL")
        void tcclRestoredOnException() throws Throwable {
            ClassLoader targetCl = mock(ClassLoader.class);
            LingInstance instance = mockInstance(targetCl);
            InvocationContext ctx = createContextWithTarget(instance);

            ClassLoader originalCl = Thread.currentThread().getContextClassLoader();

            LingInvocationFilter thrower = (c, chain) -> { throw new RuntimeException("test"); };

            assertThrows(RuntimeException.class,
                    () -> filter.doFilter(ctx, (c) -> thrower.doFilter(c, mock(LingFilterChain.class))));

            assertSame(originalCl, Thread.currentThread().getContextClassLoader());
        }
    }

    // ==================== 模拟/穿刺模式 ====================

    @Nested
    @DisplayName("模拟/穿刺模式")
    class SimulationAndGovernOnly {

        @Test
        @DisplayName("模拟模式无目标实例时跳过 TCCL 切换")
        void simulationModeSkipsWithoutTarget() throws Throwable {
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");
            ctx.setExecutionMode(InvocationExecutionMode.SIMULATION);
            // 无目标实例

            Object result = filter.doFilter(ctx, (c) -> "sim-result");
            assertEquals("sim-result", result);
        }

        @Test
        @DisplayName("穿刺模式无目标实例时跳过 TCCL 切换")
        void governOnlyModeSkipsWithoutTarget() throws Throwable {
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");
            ctx.setExecutionMode(InvocationExecutionMode.GOVERN_ONLY);
            // 无目标实例

            Object result = filter.doFilter(ctx, (c) -> "govern-result");
            assertEquals("govern-result", result);
        }
    }

    // ==================== 无目标实例异常 ====================

    @Nested
    @DisplayName("无目标实例异常")
    class NoTargetInstance {

        @Test
        @DisplayName("正常模式无目标实例抛出 ROUTE_FAILURE")
        void normalModeNoTargetThrowsRouteFailure() {
            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");
            // 非模拟非穿刺，无目标实例

            LingInvocationException thrown = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(ctx, (c) -> "unreachable"));
            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, thrown.getKind());
        }
    }

    // ==================== ClassLoader 异常 ====================

    @Nested
    @DisplayName("ClassLoader 异常")
    class ClassLoaderError {

        @Test
        @DisplayName("目标实例 ClassLoader 为 null 抛出 CLASSLOADER_ERROR")
        void nullClassLoaderThrowsError() {
            LingInstance instance = mockInstance(null);
            InvocationContext ctx = createContextWithTarget(instance);

            LingInvocationException thrown = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(ctx, (c) -> "unreachable"));
            assertEquals(LingInvocationException.ErrorKind.CLASSLOADER_ERROR, thrown.getKind());
        }

        @Test
        @DisplayName("getClassLoader 抛异常时包装为 CLASSLOADER_ERROR")
        void getClassLoaderExceptionWrapped() {
            LingInstance instance = mock(LingInstance.class);
            when(instance.getClassLoader()).thenThrow(new IllegalStateException("destroyed"));
            InvocationContext ctx = createContextWithTarget(instance);

            LingInvocationException thrown = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(ctx, (c) -> "unreachable"));
            assertEquals(LingInvocationException.ErrorKind.CLASSLOADER_ERROR, thrown.getKind());
        }
    }

    // ==================== Order ====================

    @Test
    @DisplayName("过滤器顺序为 RESOLUTION 阶段")
    void orderIsResolution() {
        assertEquals(FilterPhase.RESOLUTION, filter.getOrder());
    }
}
