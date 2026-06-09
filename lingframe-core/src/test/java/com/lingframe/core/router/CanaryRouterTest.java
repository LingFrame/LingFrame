package com.lingframe.core.router;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.TrafficRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("CanaryRouter 路由测试")
class CanaryRouterTest {

    private CanaryRouter router;
    private TrafficRouter delegate;

    @BeforeEach
    void setUp() {
        delegate = mock(TrafficRouter.class);
        router = new CanaryRouter(delegate);
    }

    @Test
    @DisplayName("候选列表为空返回 null")
    void shouldReturnNullForEmptyCandidates() {
        assertNull(router.route(Collections.emptyList(), null));
    }

    @Test
    @DisplayName("候选列表为 null 返回 null")
    void shouldReturnNullForNullCandidates() {
        assertNull(router.route(null, null));
    }

    @Test
    @DisplayName("只有一个候选时直接返回")
    void shouldReturnSingleCandidate() {
        LingInstance inst = mockInstance("ling-a", "1.0.0", false);
        assertSame(inst, router.route(Collections.singletonList(inst), null));
    }

    @Test
    @DisplayName("无灰度配置且无 labels 时委托给基础路由器")
    void shouldDelegateWhenNoCanaryConfigAndHasLabels() {
        LingInstance inst1 = mockInstance("ling-a", "1.0.0", false);
        LingInstance inst2 = mockInstance("ling-a", "2.0.0", true);

        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getLabels()).thenReturn(Collections.singletonMap("env", "test"));
        when(ctx.getRuntime()).thenReturn(null);
        when(ctx.getTargetLingId()).thenReturn(null);
        when(ctx.getServiceFQSID()).thenReturn(null);
        when(delegate.route(anyList(), eq(ctx))).thenReturn(inst1);

        LingInstance result = router.route(Arrays.asList(inst1, inst2), ctx);
        assertSame(inst1, result);
        verify(delegate).route(anyList(), eq(ctx));
    }

    @Test
    @DisplayName("setCanaryConfig 百分比越界抛出异常")
    void shouldThrowOnInvalidPercent() {
        assertThrows(InvalidArgumentException.class, () -> router.setCanaryConfig("ling-a", -1, "1.0.0"));
        assertThrows(InvalidArgumentException.class, () -> router.setCanaryConfig("ling-a", 101, "1.0.0"));
    }

    @Test
    @DisplayName("setCanaryConfig 合法百分比正常设置")
    void shouldSetValidCanaryConfig() {
        router.setCanaryConfig("ling-a", 50, "2.0.0");

        assertEquals(50, router.getCanaryPercent("ling-a"));
        assertNotNull(router.getCanaryConfig("ling-a"));
        assertEquals("2.0.0", router.getCanaryConfig("ling-a").getCanaryVersion());
    }

    @Test
    @DisplayName("getCanaryPercent 不存在时返回 0")
    void shouldReturnZeroForNonExistentConfig() {
        assertEquals(0, router.getCanaryPercent("nonexistent"));
    }

    @Test
    @DisplayName("removeCanaryConfig null 参数安全返回")
    void shouldHandleNullRemoveCanaryConfig() {
        assertDoesNotThrow(() -> router.removeCanaryConfig(null));
    }

    @Test
    @DisplayName("有灰度配置时优先匹配金丝雀版本")
    void shouldMatchCanaryByVersion() {
        LingInstance stable = mockInstance("ling-a", "1.0.0", false);
        LingInstance canary = mockInstance("ling-a", "2.0.0", true);

        router.setCanaryConfig("ling-a", 100, "2.0.0");

        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getTargetLingId()).thenReturn("ling-a");
        when(ctx.getRuntime()).thenReturn(null);
        when(ctx.getServiceFQSID()).thenReturn(null);

        LingInstance result = router.route(Arrays.asList(stable, canary), ctx);
        assertSame(canary, result);
    }

    @Test
    @DisplayName("有灰度配置但百分比为 0 时返回稳定版")
    void shouldReturnStableWhenPercentIsZero() {
        LingInstance stable = mockInstance("ling-a", "1.0.0", false);
        LingInstance canary = mockInstance("ling-a", "2.0.0", true);

        router.setCanaryConfig("ling-a", 0, "2.0.0");

        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getTargetLingId()).thenReturn("ling-a");
        when(ctx.getRuntime()).thenReturn(null);
        when(ctx.getServiceFQSID()).thenReturn(null);

        LingInstance result = router.route(Arrays.asList(stable, canary), ctx);
        assertSame(stable, result);
    }

    @Test
    @DisplayName("从 serviceFQSID 中提取 lingId")
    void shouldExtractLingIdFromFQSID() {
        LingInstance stable = mockInstance("ling-a", "1.0.0", false);
        LingInstance canary = mockInstance("ling-a", "2.0.0", true);

        router.setCanaryConfig("ling-a", 100, "2.0.0");

        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getTargetLingId()).thenReturn(null);
        when(ctx.getRuntime()).thenReturn(null);
        when(ctx.getServiceFQSID()).thenReturn("ling-a:service.echo");
        when(ctx.getLabels()).thenReturn(null);

        LingInstance result = router.route(Arrays.asList(stable, canary), ctx);
        assertSame(canary, result);
    }

    @Test
    @DisplayName("无灰度配置且无 labels 时选择稳定版实例")
    void shouldSelectStableWithoutCanaryConfig() {
        LingInstance stable = mockInstance("ling-a", "1.0.0", false);
        LingInstance canary = mockInstance("ling-a", "2.0.0", true);

        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getLabels()).thenReturn(null);
        when(ctx.getRuntime()).thenReturn(null);
        when(ctx.getTargetLingId()).thenReturn(null);
        when(ctx.getServiceFQSID()).thenReturn(null);

        LingInstance result = router.route(Arrays.asList(stable, canary), ctx);
        assertSame(stable, result);
    }

    private LingInstance mockInstance(String lingId, String version, boolean canary) {
        LingInstance inst = mock(LingInstance.class);
        LingDefinition def = mock(LingDefinition.class);
        when(inst.getDefinition()).thenReturn(def);
        when(def.getVersion()).thenReturn(version);
        when(def.getId()).thenReturn(lingId);
        Map<String, Object> props = canary ? Collections.<String, Object>singletonMap("canary", true) : Collections.<String, Object>emptyMap();
        when(def.getProperties()).thenReturn(props);
        return inst;
    }
}
