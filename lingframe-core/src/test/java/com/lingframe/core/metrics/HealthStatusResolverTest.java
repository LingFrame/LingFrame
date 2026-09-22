package com.lingframe.core.metrics;

import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 验证健康 FSM 兜底解析器（HealthStatusResolver）。
 *
 * <p>语义对齐：指标已明确（非 UNKNOWN）→ 直接采用；指标缺失/UNKNOWN → 实例池 FSM 兜底推导；
 * 解析器为纯函数，仅依赖入参，repository 查询由调用方完成。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HealthStatusResolver 健康 FSM 兜底解析器测试")
class HealthStatusResolverTest {

    @Mock
    private LingRuntime runtime;

    @Mock
    private InstancePool instancePool;

    @Mock
    private LingInstance instance;

    @Test
    @DisplayName("指标已明确(HEALTHY)时直接采用, 不依赖运行时")
    void prefersMetricStatusWhenResolved() {
        assertEquals("HEALTHY", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.HEALTHY, null));
    }

    @Test
    @DisplayName("指标已明确(WARNING)时直接采用, 不依赖运行时")
    void prefersMetricStatusForWarning() {
        assertEquals("WARNING", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.WARNING, null));
    }

    @Test
    @DisplayName("指标为空且运行时为空 → UNKNOWN")
    void unknownWhenMetricUnknownAndRuntimeNull() {
        assertEquals("UNKNOWN", HealthStatusResolver.resolve(null, null));
        assertEquals("UNKNOWN", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, null));
    }

    @Test
    @DisplayName("实例池未初始化 → UNKNOWN")
    void unknownWhenInstancePoolMissing() {
        assertEquals("UNKNOWN", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("无活跃实例 → UNKNOWN")
    void unknownWhenNoActiveInstances() {
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Collections.emptyList());
        assertEquals("UNKNOWN", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("活跃实例已销毁 → UNHEALTHY")
    void unhealthyWhenInstanceDestroyed() {
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
        when(instance.isDestroyed()).thenReturn(true);
        assertEquals("UNHEALTHY", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("活跃实例垂死 → UNHEALTHY")
    void unhealthyWhenInstanceDying() {
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
        when(instance.isDying()).thenReturn(true);
        assertEquals("UNHEALTHY", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("活跃实例 FSM 进入 DEAD → UNHEALTHY")
    void unhealthyWhenInstanceDead() {
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
        when(instance.currentStatus()).thenReturn(InstanceStatus.DEAD);
        assertEquals("UNHEALTHY", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("活跃实例 FSM 进入 ERROR → UNHEALTHY")
    void unhealthyWhenInstanceError() {
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
        when(instance.currentStatus()).thenReturn(InstanceStatus.ERROR);
        assertEquals("UNHEALTHY", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("存在就绪实例 → HEALTHY")
    void healthyWhenInstanceReady() {
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
        when(instance.isReady()).thenReturn(true);
        assertEquals("HEALTHY", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("全部处于过渡态(未就绪且无异常) → UNKNOWN 最诚实")
    void unknownWhenAllInstancesTransitioning() {
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
        // isReady/isDestroyed/isDying 默认 false, currentStatus 默认 null → 过渡态
        assertEquals("UNKNOWN", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("多实例: 混有就绪实例 → HEALTHY")
    void healthyWhenMixedWithReadyInstance() {
        LingInstance transitioning = Mockito.mock(LingInstance.class);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Arrays.asList(transitioning, instance));
        when(instance.isReady()).thenReturn(true);
        assertEquals("HEALTHY", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }

    @Test
    @DisplayName("多实例: 任一异常实例优先判定 → UNHEALTHY")
    void unhealthyWhenAnyInstanceBrokenAmongMany() {
        LingInstance transitioning = Mockito.mock(LingInstance.class);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(instancePool.getActiveInstances()).thenReturn(Arrays.asList(transitioning, instance));
        when(instance.isDestroyed()).thenReturn(true);
        assertEquals("UNHEALTHY", HealthStatusResolver.resolve(MetricsSnapshot.HealthStatus.UNKNOWN, runtime));
    }
}
