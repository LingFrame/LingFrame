package com.lingframe.core.routing;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.FilterPhase;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.RoutableTarget;
import com.lingframe.core.spi.TrafficRouter;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * InstanceRoutingFilter 测试。
 * <p>
 * 覆盖 ROUTING 阶段实例路由：短路放行（已选实例/runtime 未设）、
 * 灵核路径（NORMAL 取单例设 targetInstance / SIMULATION·GOVERN_ONLY 放行 / 空池抛异常）、
 * NORMAL 实例选择（TrafficRouter/兜底默认/兜底首位）、候选空处理（SIMULATION 放行/NORMAL 抛异常）、
 * TrafficRouter 返回 null 抛异常、迭代期版本过滤。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InstanceRoutingFilter 测试")
class InstanceRoutingFilterTest {

    @Mock
    private LingFilterChain filterChain;

    private InvocationContext context;

    @BeforeEach
    void setUp() {
        context = InvocationContext.obtain();
        context.setServiceFQSID("__provider__:com.example.UserService");
    }

    @AfterEach
    void tearDown() {
        context.recycle();
    }

    /** 构造带 ready 实例池的灵元 runtime mock */
    private LingRuntime mockLingRuntime(List<LingInstance> readyInstances) {
        LingRuntime runtime = mock(LingRuntime.class);
        when(runtime.getReadyInstances()).thenReturn(readyInstances);
        return runtime;
    }

    /** 构造带 lingId/version 的实例 mock（lenient：不同分支只用到部分 getter） */
    private LingInstance mockInstance(String lingId, String version) {
        LingInstance instance = mock(LingInstance.class);
        lenient().when(instance.getLingId()).thenReturn(lingId);
        lenient().when(instance.getVersion()).thenReturn(version);
        return instance;
    }

    // ==================== 顺序契约 ====================

    @Test
    @DisplayName("过滤器顺序为 ROUTING 阶段（200）")
    void orderIsRouting() {
        InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
        assertEquals(FilterPhase.ROUTING, filter.getOrder());
        assertEquals(200, filter.getOrder());
    }

    // ==================== 短路放行 ====================

    @Nested
    @DisplayName("短路放行场景")
    class PassThroughCases {

        @Test
        @DisplayName("targetInstance 已设（入口预解析/旧格式 FQSID）时直接透传，不调 TrafficRouter")
        void targetInstancePresetPassThrough() throws Throwable {
            TrafficRouter router = mock(TrafficRouter.class);
            InstanceRoutingFilter filter = new InstanceRoutingFilter(router);
            LingInstance preset = mockInstance("ling-a", "1.0.0");
            context.routing().setTargetInstance(preset);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            verify(filterChain).doFilter(context);
            verifyNoInteractions(router);
        }

        @Test
        @DisplayName("runtime 未设时放行（SIMULATION/GOVERN_ONLY 借道治理）")
        void runtimeNullPassThrough() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertNull(context.routing().getTargetInstance());
        }
    }

    // ==================== 灵核路径 ====================

    @Nested
    @DisplayName("灵核路径（runtime 非 LingRuntime）")
    class CoreRuntimeCases {

        @Test
        @DisplayName("NORMAL 模式从灵核单例实例池取实例设为 targetInstance")
        void coreNormalSetsTargetInstance() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            LingInstance coreInstance = mockInstance("lingcore", null);
            RoutableTarget coreRuntime = mock(RoutableTarget.class);
            when(coreRuntime.getReadyInstances()).thenReturn(Collections.singletonList(coreInstance));
            context.setRuntime(coreRuntime);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertSame(coreInstance, context.routing().getTargetInstance(),
                    "灵核 NORMAL 模式应取单例实例设为 targetInstance");
            assertEquals("lingcore", context.getTargetLingId());
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("SIMULATION 模式放行，不选实例（借道治理）")
        void coreSimulationPassThrough() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            RoutableTarget coreRuntime = mock(RoutableTarget.class);
            context.setRuntime(coreRuntime);
            context.execution().setMode(InvocationExecutionMode.SIMULATION);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertNull(context.routing().getTargetInstance(),
                    "灵核 SIMULATION 模式借道治理，不选实例");
        }

        @Test
        @DisplayName("GOVERN_ONLY 模式放行，不选实例（借道治理）")
        void coreGovernOnlyPassThrough() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            RoutableTarget coreRuntime = mock(RoutableTarget.class);
            context.setRuntime(coreRuntime);
            context.execution().setMode(InvocationExecutionMode.GOVERN_ONLY);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertNull(context.routing().getTargetInstance(),
                    "灵核 GOVERN_ONLY 模式借道治理，不选实例");
        }

        @Test
        @DisplayName("NORMAL 模式灵核实例池为空时抛 ROUTE_FAILURE")
        void coreNormalEmptyThrowsRouteFailure() {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            RoutableTarget coreRuntime = mock(RoutableTarget.class);
            when(coreRuntime.getReadyInstances()).thenReturn(Collections.emptyList());
            context.setRuntime(coreRuntime);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
            verifyNoInteractions(filterChain);
        }
    }

    // ==================== NORMAL 实例选择 ====================

    @Nested
    @DisplayName("NORMAL 模式实例选择")
    class NormalSelection {

        @Test
        @DisplayName("有 TrafficRouter 时调用 route 并回填实例")
        void selectViaTrafficRouter() throws Throwable {
            TrafficRouter router = mock(TrafficRouter.class);
            InstanceRoutingFilter filter = new InstanceRoutingFilter(router);
            LingInstance target = mockInstance("ling-a", "1.0.0");
            LingRuntime runtime = mockLingRuntime(Collections.singletonList(target));
            context.setRuntime(runtime);
            when(router.route(any(), eq(context))).thenReturn(target);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertSame(target, context.routing().getTargetInstance());
            assertEquals("ling-a", context.getTargetLingId());
            assertEquals("1.0.0", context.getTargetVersion());
            verify(filterChain).doFilter(context);
        }

        @Test
        @DisplayName("无 TrafficRouter 时兜底 getDefault 实例")
        void fallbackToDefaultInstance() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            LingInstance defaultInst = mockInstance("ling-a", "1.0.0");
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(defaultInst));
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getDefault()).thenReturn(defaultInst);
            context.setRuntime(runtime);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertSame(defaultInst, context.routing().getTargetInstance());
        }

        @Test
        @DisplayName("无 TrafficRouter 且 getDefault 为 null 时兜底候选首位")
        void fallbackToFirstCandidateWhenDefaultNull() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            LingInstance first = mockInstance("ling-a", "1.0.0");
            LingInstance second = mockInstance("ling-a", "1.0.0");
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getReadyInstances()).thenReturn(Arrays.asList(first, second));
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getDefault()).thenReturn(null);
            context.setRuntime(runtime);
            when(filterChain.doFilter(context)).thenReturn("ok");

            filter.doFilter(context, filterChain);

            assertSame(first, context.routing().getTargetInstance());
        }

        @Test
        @DisplayName("无 TrafficRouter 且迭代期版本锁定时，getDefault 返回旧版本不误选")
        void fallbackDefaultRespectsVersionFilter() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            LingInstance v1 = mockInstance("ling-a", "1.0.0");
            LingInstance v2 = mockInstance("ling-a", "2.0.0");
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            // READY 池含 v1+v2，但 L0 锁定 2.0.0，候选过滤后只剩 v2
            when(runtime.getReadyInstances()).thenReturn(Arrays.asList(v1, v2));
            when(runtime.getInstancePool()).thenReturn(pool);
            // getDefault 返回稳定版 v1（InstancePool 默认实例通常是稳定版）
            when(pool.getDefault()).thenReturn(v1);
            context.setRuntime(runtime);
            context.setTargetVersion("2.0.0");
            when(filterChain.doFilter(context)).thenReturn("ok");

            filter.doFilter(context, filterChain);

            // 必须选 v2（版本锁定），而非 getDefault 的 v1
            assertSame(v2, context.routing().getTargetInstance());
            assertEquals("2.0.0", context.getTargetVersion(), "targetVersion 不被旧版本覆盖");
        }
    }

    // ==================== 候选空处理 ====================

    @Nested
    @DisplayName("候选空处理")
    class EmptyCandidates {

        @Test
        @DisplayName("NORMAL 模式候选空时抛 ROUTE_FAILURE")
        void normalEmptyThrowsRouteFailure() {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            LingRuntime runtime = mockLingRuntime(Collections.emptyList());
            context.setRuntime(runtime);

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> filter.doFilter(context, filterChain));

            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
            verifyNoInteractions(filterChain);
        }

        @Test
        @DisplayName("SIMULATION 模式候选空时放行（借道治理）")
        void simulationEmptyPassThrough() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            LingRuntime runtime = mockLingRuntime(Collections.emptyList());
            context.setRuntime(runtime);
            context.execution().setMode(InvocationExecutionMode.SIMULATION);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
            assertNull(context.routing().getTargetInstance());
        }

        @Test
        @DisplayName("GOVERN_ONLY 模式候选空时放行（借道治理）")
        void governOnlyEmptyPassThrough() throws Throwable {
            InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
            LingRuntime runtime = mockLingRuntime(Collections.emptyList());
            context.setRuntime(runtime);
            context.execution().setMode(InvocationExecutionMode.GOVERN_ONLY);
            Object expected = new Object();
            when(filterChain.doFilter(context)).thenReturn(expected);

            Object result = filter.doFilter(context, filterChain);

            assertSame(expected, result);
        }
    }

    // ==================== TrafficRouter 返回 null ====================

    @Test
    @DisplayName("TrafficRouter.route 返回 null 时抛 ROUTE_FAILURE")
    void trafficRouterReturnNullThrowsRouteFailure() {
        TrafficRouter router = mock(TrafficRouter.class);
        InstanceRoutingFilter filter = new InstanceRoutingFilter(router);
        LingInstance candidate = mockInstance("ling-a", "1.0.0");
        LingRuntime runtime = mockLingRuntime(Collections.singletonList(candidate));
        context.setRuntime(runtime);
        when(router.route(any(), eq(context))).thenReturn(null);

        LingInvocationException ex = assertThrows(LingInvocationException.class,
                () -> filter.doFilter(context, filterChain));

        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
        verifyNoInteractions(filterChain);
    }

    // ==================== 迭代期版本过滤 ====================

    @Test
    @DisplayName("迭代期 targetVersion 非空时只把匹配版本实例交给 TrafficRouter")
    void versionFilterOnlyMatchedCandidates() throws Throwable {
        TrafficRouter router = mock(TrafficRouter.class);
        InstanceRoutingFilter filter = new InstanceRoutingFilter(router);
        LingInstance v1 = mockInstance("ling-a", "1.0.0");
        LingInstance v2 = mockInstance("ling-a", "2.0.0");
        LingRuntime runtime = mockLingRuntime(Arrays.asList(v1, v2));
        context.setRuntime(runtime);
        // L0 阶段已锁定版本 2.0.0（迭代期灰度）
        context.setTargetVersion("2.0.0");
        when(router.route(eq(Collections.singletonList(v2)), eq(context))).thenReturn(v2);
        when(filterChain.doFilter(context)).thenReturn("ok");

        filter.doFilter(context, filterChain);

        assertSame(v2, context.routing().getTargetInstance());
        // 验证 TrafficRouter 只收到 v2 实例，未误选旧版本
        verify(router).route(eq(Collections.singletonList(v2)), eq(context));
    }

    @Test
    @DisplayName("迭代期 targetVersion 非空且无匹配版本实例时，NORMAL 抛 ROUTE_FAILURE")
    void versionFilterNoMatchThrowsRouteFailure() {
        InstanceRoutingFilter filter = new InstanceRoutingFilter(null);
        LingInstance v1 = mockInstance("ling-a", "1.0.0");
        LingRuntime runtime = mockLingRuntime(Collections.singletonList(v1));
        context.setRuntime(runtime);
        context.setTargetVersion("2.0.0");

        LingInvocationException ex = assertThrows(LingInvocationException.class,
                () -> filter.doFilter(context, filterChain));

        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
        verifyNoInteractions(filterChain);
    }
}
