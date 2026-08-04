package com.lingframe.dashboard.service;

import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.dashboard.dto.LeakDetectionRecordDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("泄漏检测缓存服务测试")
class LeakDetectionCacheServiceTest {

    private EventBus eventBus;
    private LeakDetectionCacheService service;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        service = new LeakDetectionCacheService(eventBus);
        service.init();
    }

    /**
     * LeakDetectionEvent 是异步事件，发布后需轮询等待监听器处理完成。
     */
    private List<LeakDetectionRecordDTO> awaitRecords(int expectedSize) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        List<LeakDetectionRecordDTO> records = service.getRecords();
        while (records.size() < expectedSize && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            records = service.getRecords();
        }
        return records;
    }

    @Test
    @DisplayName("收到泄漏事件后应缓存记录")
    void shouldCacheLeakDetectionEvent() throws InterruptedException {
        eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                "user-ling", "1.0.0", false,
                "ClassLoader remained alive", "DEV_AGGRESSIVE",
                System.currentTimeMillis() - 5000));

        List<LeakDetectionRecordDTO> records = awaitRecords(1);
        assertEquals(1, records.size());
        assertEquals("user-ling", records.get(0).getLingId());
        assertFalse(records.get(0).isCollected(), "未回收记录应置顶");
    }

    @Test
    @DisplayName("同一灵元版本的新事件应覆盖旧记录")
    void shouldOverwriteSameVersionRecord() throws InterruptedException {
        eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                "user-ling", "1.0.0", false, "leak", "DEV_AGGRESSIVE", 1000));
        awaitRecords(1);
        eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                "user-ling", "1.0.0", true, "collected", "DEV_AGGRESSIVE", 1000));
        // 等待覆盖后的记录（size 仍为 1，但 collected 变为 true）
        Thread.sleep(100);

        List<LeakDetectionRecordDTO> records = service.getRecords();
        assertEquals(1, records.size(), "同版本应覆盖，不新增");
        assertTrue(records.get(0).isCollected(), "应为最新回收结果");
    }

    @Test
    @DisplayName("未回收记录应排在已回收之前")
    void shouldSortUncollectedFirst() throws InterruptedException {
        eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                "ling-a", "1.0", true, "ok", "PROD_PASSIVE", 1000));
        eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                "ling-b", "1.0", false, "leak", "PROD_PASSIVE", 1000));

        List<LeakDetectionRecordDTO> records = awaitRecords(2);
        assertFalse(records.get(0).isCollected(), "未回收的应排第一");
        assertTrue(records.get(1).isCollected());
    }

    @Test
    @DisplayName("销毁时应取消订阅")
    void shouldUnsubscribeOnDestroy() throws InterruptedException {
        service.destroy();
        eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                "ling-x", "1.0", false, "leak", "PROD_PASSIVE", 1000));
        Thread.sleep(200);
        assertTrue(service.getRecords().isEmpty(), "销毁后不应再缓存事件");
    }
}

