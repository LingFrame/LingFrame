package com.lingframe.dashboard.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dashboard 生命周期事件存储，集中封装时间线追加与裁剪逻辑。
 */
public class DashboardLifecycleEventStore {

    private static final int MAX_EVENTS = 1000;

    private final List<DashboardService.LifecycleEvent> events = Collections.synchronizedList(new ArrayList<>());

    public List<DashboardService.LifecycleEvent> getEvents(String lingId) {
        if (lingId == null || lingId.isEmpty()) {
            return new ArrayList<>(events);
        }
        return events.stream()
                .filter(event -> lingId.equals(event.getLingId()))
                .collect(Collectors.toList());
    }

    public void addEvent(String lingId, String version, String type, String title, String description) {
        events.add(new DashboardService.LifecycleEvent(lingId, version, type, title, description));
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }
}
