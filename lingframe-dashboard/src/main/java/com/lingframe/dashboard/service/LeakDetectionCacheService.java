package com.lingframe.dashboard.service;

import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.dashboard.dto.LeakDetectionRecordDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 泄漏检测记录缓存服务。
 * <p>
 * 订阅 LeakDetectionEvent，按 lingId:version 缓存最新检测结果。
 * 未回收的记录（collected=false）置顶排序，最多保留 100 条。
 * <p>
 * 使用 Spring 生命周期接口兼容 SB2/SB3，避免 javax/jakarta.annotation 差异。
 */
@Slf4j
public class LeakDetectionCacheService implements InitializingBean, DisposableBean {

    private static final int MAX_RECORDS = 100;

    private final EventBus eventBus;
    private final Map<String, LeakDetectionRecordDTO> cache = new ConcurrentHashMap<>();

    /** 保存监听器引用，便于销毁时取消订阅 */
    private final LingEventListener<MonitoringEvents.LeakDetectionEvent> listener = this::handleEvent;

    public LeakDetectionCacheService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public void afterPropertiesSet() {
        init();
    }

    public void init() {
        eventBus.subscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, listener);
    }

    @Override
    public void destroy() {
        eventBus.unsubscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, listener);
    }

    private void handleEvent(MonitoringEvents.LeakDetectionEvent event) {
        if (event == null || event.getLingId() == null) return;

        String key = event.getLingId() + ":" + (event.getVersion() == null ? "" : event.getVersion());
        long elapsed = Math.max(0L, event.getTimestamp() - event.getTriggerTimeMillis());

        LeakDetectionRecordDTO record = LeakDetectionRecordDTO.builder()
                .lingId(event.getLingId())
                .version(event.getVersion())
                .collected(event.isCollected())
                .message(event.getMessage())
                .detectionMode(event.getDetectionMode())
                .triggerTimeMillis(event.getTriggerTimeMillis())
                .timestamp(event.getTimestamp())
                .elapsedMillis(elapsed)
                .build();

        cache.put(key, record);
        evictIfNeeded();
    }

    /**
     * 获取所有泄漏检测记录，未回收的置顶，按时间倒序。
     */
    public List<LeakDetectionRecordDTO> getRecords() {
        return cache.values().stream()
                .sorted(Comparator
                        .comparing(LeakDetectionRecordDTO::isCollected)
                        .thenComparing(LeakDetectionRecordDTO::getTimestamp, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    private void evictIfNeeded() {
        if (cache.size() <= MAX_RECORDS) return;

        // 优先淘汰已回收的最旧记录
        cache.values().stream()
                .filter(LeakDetectionRecordDTO::isCollected)
                .min(Comparator.comparing(LeakDetectionRecordDTO::getTimestamp))
                .ifPresent(oldest -> {
                    String key = oldest.getLingId() + ":" + (oldest.getVersion() == null ? "" : oldest.getVersion());
                    cache.remove(key);
                });
    }
}
