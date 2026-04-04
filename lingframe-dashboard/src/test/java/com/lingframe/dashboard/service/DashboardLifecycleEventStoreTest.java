package com.lingframe.dashboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("DashboardLifecycleEventStore 测试")
class DashboardLifecycleEventStoreTest {

    @Test
    @DisplayName("应按 lingId 过滤时间线事件")
    void shouldFilterEventsByLingId() {
        DashboardLifecycleEventStore store = new DashboardLifecycleEventStore();

        store.addEvent("ling-a", "1.0.0", "READY", "灵元安装完成", "A");
        store.addEvent("ling-b", "1.0.0", "READY", "灵元安装完成", "B");
        store.addEvent("ling-a", "1.0.1", "ACTIVE", "灵元激活", "C");

        List<DashboardService.LifecycleEvent> events = store.getEvents("ling-a");

        assertEquals(2, events.size());
        assertEquals("ling-a", events.get(0).getLingId());
        assertEquals("ling-a", events.get(1).getLingId());
    }

    @Test
    @DisplayName("应只保留最近一千条时间线事件")
    void shouldKeepOnlyLatestThousandEvents() {
        DashboardLifecycleEventStore store = new DashboardLifecycleEventStore();

        for (int i = 0; i < 1005; i++) {
            store.addEvent("ling-a", "1.0." + i, "READY", "灵元安装完成", "event-" + i);
        }

        List<DashboardService.LifecycleEvent> events = store.getEvents(null);

        assertEquals(1000, events.size());
        assertEquals("1.0.5", events.get(0).getVersion());
        assertEquals("event-1004", events.get(999).getDescription());
    }
}
