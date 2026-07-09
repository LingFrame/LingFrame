package com.lingframe.api.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LingCallContext 单元测试")
class LingCallContextTest {

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
    }

    @Test
    @DisplayName("测试 LingId 存取")
    void testLingId() {
        assertNull(LingCallContext.getLingId());
        LingCallContext.setLingId("ling-x");
        assertEquals("ling-x", LingCallContext.getLingId());
        
        LingCallContext.clear();
        assertNull(LingCallContext.getLingId());
    }

    @Test
    @DisplayName("测试 Labels 标签存取")
    void testLabels() {
        // 未设置时返回空 Map（非 null），避免调用方 NPE
        assertTrue(LingCallContext.getLabels().isEmpty());
        Map<String, String> labels = new HashMap<>();
        labels.put("env", "prod");
        LingCallContext.setLabels(labels);
        
        assertEquals(labels, LingCallContext.getLabels());
        assertEquals("prod", LingCallContext.getLabels().get("env"));
    }

    @Test
    @DisplayName("测试 TraceId 自动与手动存取")
    void testTraceId() {
        assertNull(LingCallContext.getTraceId());
        
        // startTrace 会自动生成
        String autoTraceId = LingCallContext.startTrace();
        assertNotNull(autoTraceId);
        assertEquals(autoTraceId, LingCallContext.getTraceId());
        
        // startTrace 不会重复生成
        assertEquals(autoTraceId, LingCallContext.startTrace());

        // 手动 setTraceId
        LingCallContext.setTraceId("manual-trace-id");
        assertEquals("manual-trace-id", LingCallContext.getTraceId());
        
        // 手动传入空值应触发自动生成
        LingCallContext.setTraceId("");
        assertNotNull(LingCallContext.getTraceId());
        assertTrue(LingCallContext.getTraceId().length() > 0);
        
        LingCallContext.clearTraceId();
        assertNull(LingCallContext.getTraceId());
    }

    @Test
    @DisplayName("测试调用深度递增递减")
    void testDepth() {
        assertEquals(0, LingCallContext.getDepth());
        
        LingCallContext.increaseDepth();
        assertEquals(1, LingCallContext.getDepth());
        
        LingCallContext.increaseDepth();
        assertEquals(2, LingCallContext.getDepth());
        
        LingCallContext.decreaseDepth();
        assertEquals(1, LingCallContext.getDepth());
        
        // 深度减到0后不再继续扣减为负数
        LingCallContext.decreaseDepth();
        LingCallContext.decreaseDepth();
        assertEquals(0, LingCallContext.getDepth());
    }
}
