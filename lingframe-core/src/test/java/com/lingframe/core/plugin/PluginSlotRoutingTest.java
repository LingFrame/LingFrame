package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.spi.PluginContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*; // 建议引入 Mockito，若无则手动实现简易 Stub

class PluginSlotRoutingTest {
    private PluginSlot slot;
    private PluginContext mockCtx;

    @BeforeEach
    void setUp() {
        slot = new PluginSlot("user-plugin", Executors.newSingleThreadScheduledExecutor(), null, null);
        mockCtx = mock(PluginContext.class);
    }

    @Test
    void testDefaultRouting() {
        PluginInstance stable = new PluginInstance("v1.0", mock(PluginContainer.class));
        slot.addInstance(stable, mockCtx, true);

        // 无标签请求 -> 命中默认版
        InvocationContext req = InvocationContext.builder().build();
        assertEquals("v1.0", slot.selectInstance(req).getVersion());
    }

    @Test
    void testLabelBasedRouting() {
        PluginInstance stable = new PluginInstance("v1.0", mock(PluginContainer.class));
        PluginInstance canary = new PluginInstance("v1.1", mock(PluginContainer.class));
        canary.getLabels().put("env", "canary");

        slot.addInstance(stable, mockCtx, true);
        slot.addInstance(canary, mockCtx, false);

        // 携带 canary 标签 -> 命中金丝雀版
        InvocationContext req = InvocationContext.builder()
                .labels(Map.of("env", "canary"))
                .build();
        assertEquals("v1.1", slot.selectInstance(req).getVersion());
    }

    @Test
    void testBestMatchRouting() {
        PluginInstance v1 = new PluginInstance("v1.0", mock(PluginContainer.class)); // Default
        PluginInstance v2 = new PluginInstance("v1.1-T1", mock(PluginContainer.class));
        v2.getLabels().put("tenant", "T1");

        PluginInstance v3 = new PluginInstance("v1.1-T1-Canary", mock(PluginContainer.class));
        v3.getLabels().put("tenant", "T1");
        v3.getLabels().put("env", "canary");

        slot.addInstance(v1, mockCtx, true);
        slot.addInstance(v2, mockCtx, false);
        slot.addInstance(v3, mockCtx, false);

        // 同时匹配 tenant=T1 和 env=canary -> 命中得分最高(最精准)的 v3
        InvocationContext req = InvocationContext.builder()
                .labels(Map.of("tenant", "T1", "env", "canary"))
                .build();
        assertEquals("v1.1-T1-Canary", slot.selectInstance(req).getVersion());
    }
}