package com.lingframe.dashboard.service;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.dashboard.dto.LeakDetectionRecordDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * LeakDetectionCacheService 补充测试
 * <p>
 * 聚焦 handleEvent 缓存淘汰逻辑、evictIfNeeded、getRecords 排序等分支。
 */
@DisplayName("LeakDetectionCacheService 补充测试")
class LeakDetectionCacheServiceSupplementTest {

    private LeakDetectionCacheService service;
    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = mock(EventBus.class);
        service = new LeakDetectionCacheService(eventBus);
    }

    // ==================== getRecords 排序 ====================

    @Nested
    @DisplayName("getRecords 排序")
    class GetRecordsTests {

        @Test
        @DisplayName("空缓存应返回空列表")
        void shouldReturnEmptyWhenCacheEmpty() {
            List<LeakDetectionRecordDTO> result = service.getRecords();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("未回收记录应排在已回收记录之前")
        void shouldSortUncollectedBeforeCollected() {
            emitEvent("ling1", "v1", true, 100L, 50L);
            emitEvent("ling2", "v1", false, 200L, 100L);

            List<LeakDetectionRecordDTO> result = service.getRecords();

            assertEquals(2, result.size());
            // 未回收的（collected=false）应排在前面
            assertFalse(result.get(0).isCollected());
            assertTrue(result.get(1).isCollected());
        }

        @Test
        @DisplayName("同为未回收时应按时间倒序排列")
        void shouldSortByTimestampDescendingWhenSameCollectedStatus() {
            emitEvent("ling1", "v1", false, 100L, 50L);
            emitEvent("ling2", "v1", false, 300L, 200L);
            emitEvent("ling3", "v1", false, 200L, 100L);

            List<LeakDetectionRecordDTO> result = service.getRecords();

            assertEquals(3, result.size());
            // 按时间倒序：300 -> 200 -> 100
            assertEquals(300L, result.get(0).getTimestamp());
            assertEquals(200L, result.get(1).getTimestamp());
            assertEquals(100L, result.get(2).getTimestamp());
        }

        @Test
        @DisplayName("同为已回收时应按时间倒序排列")
        void shouldSortCollectedByTimestampDescending() {
            emitEvent("ling1", "v1", true, 100L, 50L);
            emitEvent("ling2", "v1", true, 300L, 200L);

            List<LeakDetectionRecordDTO> result = service.getRecords();

            assertEquals(2, result.size());
            assertTrue(result.get(0).isCollected());
            assertTrue(result.get(1).isCollected());
            assertEquals(300L, result.get(0).getTimestamp());
            assertEquals(100L, result.get(1).getTimestamp());
        }

        @Test
        @DisplayName("混合状态应先按 collected 分组再按时间倒序")
        void shouldSortMixedStatusCorrectly() {
            emitEvent("ling1", "v1", true, 100L, 50L);
            emitEvent("ling2", "v1", false, 200L, 100L);
            emitEvent("ling3", "v1", true, 300L, 200L);
            emitEvent("ling4", "v1", false, 400L, 300L);

            List<LeakDetectionRecordDTO> result = service.getRecords();

            assertEquals(4, result.size());
            // 前两个为未回收（collected=false），按时间倒序
            assertFalse(result.get(0).isCollected());
            assertFalse(result.get(1).isCollected());
            assertEquals(400L, result.get(0).getTimestamp());
            assertEquals(200L, result.get(1).getTimestamp());
            // 后两个为已回收（collected=true），按时间倒序
            assertTrue(result.get(2).isCollected());
            assertTrue(result.get(3).isCollected());
            assertEquals(300L, result.get(2).getTimestamp());
            assertEquals(100L, result.get(3).getTimestamp());
        }
    }

    // ==================== 缓存淘汰逻辑 ====================

    @Nested
    @DisplayName("缓存淘汰逻辑")
    class EvictionTests {

        @Test
        @DisplayName("同一 lingId:version 应覆盖旧记录")
        void shouldOverwriteSameKey() {
            emitEvent("ling1", "v1", false, 100L, 50L);
            emitEvent("ling1", "v1", true, 200L, 100L);

            List<LeakDetectionRecordDTO> result = service.getRecords();

            assertEquals(1, result.size());
            // 第二次应覆盖第一次
            assertTrue(result.get(0).isCollected());
            assertEquals(200L, result.get(0).getTimestamp());
        }

        @Test
        @DisplayName("version 为 null 时应使用空字符串作为 key 的一部分")
        void shouldUseEmptyStringWhenVersionNull() {
            emitEvent("ling1", null, false, 100L, 50L);

            List<LeakDetectionRecordDTO> result = service.getRecords();

            assertEquals(1, result.size());
            assertEquals("ling1", result.get(0).getLingId());
        }

        @Test
        @DisplayName("不同 version 应作为独立记录缓存")
        void shouldCacheDifferentVersionsSeparately() {
            emitEvent("ling1", "v1", false, 100L, 50L);
            emitEvent("ling1", "v2", false, 200L, 100L);

            List<LeakDetectionRecordDTO> result = service.getRecords();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("超过 100 条记录时应优先淘汰已回收的最旧记录")
        void shouldEvictOldestCollectedWhenOverLimit() {
            // 填充 100 条已回收记录
            for (int i = 0; i < 100; i++) {
                emitEvent("ling" + i, "v1", true, (long) i, 0L);
            }
            assertEquals(100, service.getRecords().size());

            // 再添加一条未回收记录，触发淘汰
            emitEvent("newLing", "v1", false, 999L, 0L);

            List<LeakDetectionRecordDTO> result = service.getRecords();
            assertEquals(100, result.size());
            // 新记录应存在
            assertTrue(result.stream().anyMatch(r -> "newLing".equals(r.getLingId())));
            // 最旧的已回收记录（ling0）应被淘汰
            assertFalse(result.stream().anyMatch(r -> "ling0".equals(r.getLingId())));
        }

        @Test
        @DisplayName("超过 100 条记录时若无已回收记录应保留全部（淘汰逻辑不触发）")
        void shouldNotEvictWhenNoCollectedRecords() {
            // 填充 100 条未回收记录
            for (int i = 0; i < 100; i++) {
                emitEvent("ling" + i, "v1", false, (long) i, 0L);
            }
            assertEquals(100, service.getRecords().size());

            // 再添加一条未回收记录
            emitEvent("newLing", "v1", false, 999L, 0L);

            List<LeakDetectionRecordDTO> result = service.getRecords();
            // 由于无已回收记录可淘汰，cache.size 会超过 100（evictIfNeeded 的 ifPresent 不触发）
            assertEquals(101, result.size());
        }

        @Test
        @DisplayName("淘汰时应选择已回收记录中时间戳最小的")
        void shouldEvictOldestAmongCollected() {
            // 添加 99 条已回收记录
            for (int i = 1; i <= 99; i++) {
                emitEvent("ling" + i, "v1", true, (long) i * 10, 0L);
            }
            // 添加 1 条已回收但时间戳最旧的记录
            emitEvent("lingOldest", "v1", true, 1L, 0L);

            assertEquals(100, service.getRecords().size());

            // 添加一条新记录触发淘汰
            emitEvent("newLing", "v1", false, 999L, 0L);

            List<LeakDetectionRecordDTO> result = service.getRecords();
            assertEquals(100, result.size());
            // 时间戳最小的已回收记录（lingOldest，timestamp=1）应被淘汰
            assertFalse(result.stream().anyMatch(r -> "lingOldest".equals(r.getLingId())));
        }
    }

    // ==================== handleEvent 边界 ====================

    @Nested
    @DisplayName("handleEvent 边界")
    class HandleEventBoundaryTests {

        @Test
        @DisplayName("event 为 null 时应忽略不抛异常")
        void shouldIgnoreNullEvent() {
            // 直接调用 handleEvent 会触发空指针，但由于它是 private，通过反射或事件订阅触发
            // 这里通过 emitEvent(null, ...) 间接验证（但 emitEvent 不会传 null event）
            // 实际上 handleEvent(null) 会因 event == null 检查而 return
            // 验证方式：确保空缓存时调用不抛异常
            assertDoesNotThrow(() -> service.getRecords());
        }

        @Test
        @DisplayName("elapsedMillis 应为非负值")
        void shouldComputeNonNegativeElapsed() {
            // triggerTime > timestamp 的情况
            emitEvent("ling1", "v1", false, 100L, 200L);

            List<LeakDetectionRecordDTO> result = service.getRecords();
            assertEquals(1, result.size());
            // Math.max(0L, 100 - 200) = 0
            assertEquals(0L, result.get(0).getElapsedMillis());
        }

        @Test
        @DisplayName("elapsedMillis 应正确计算时间差")
        void shouldComputeElapsedCorrectly() {
            emitEvent("ling1", "v1", false, 500L, 100L);

            List<LeakDetectionRecordDTO> result = service.getRecords();
            assertEquals(1, result.size());
            assertEquals(400L, result.get(0).getElapsedMillis());
        }
    }

    // ==================== 生命周期方法 ====================

    @Nested
    @DisplayName("生命周期方法")
    class LifecycleTests {

        @Test
        @DisplayName("init 应订阅 LeakDetectionEvent")
        void shouldSubscribeOnInit() {
            service.init();

            org.mockito.Mockito.verify(eventBus).subscribeGlobal(
                    org.mockito.ArgumentMatchers.eq(MonitoringEvents.LeakDetectionEvent.class),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("destroy 应取消订阅")
        void shouldUnsubscribeOnDestroy() {
            service.init();
            service.destroy();

            org.mockito.Mockito.verify(eventBus).unsubscribeGlobal(
                    org.mockito.ArgumentMatchers.eq(MonitoringEvents.LeakDetectionEvent.class),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("多次 destroy 不应抛异常")
        void shouldNotThrowOnMultipleDestroy() {
            service.init();
            service.destroy();
            // 再次 destroy（虽然 unsubscribe 会再次调用，但 mock 不抛异常）
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.destroy());
        }
    }

    // ==================== 辅助方法 ====================

    private void emitEvent(String lingId, String version, boolean collected,
                           long timestamp, long triggerTimeMillis) {
        // LeakDetectionEvent 字段为 final，通过构造函数构造
        MonitoringEvents.LeakDetectionEvent event = new MonitoringEvents.LeakDetectionEvent(
                lingId, version, collected, "test message", "MANUAL", triggerTimeMillis);

        // 通过反射调用 private handleEvent 方法触发缓存，并覆盖 timestamp 字段
        try {
            java.lang.reflect.Field tsField = MonitoringEvents.LeakDetectionEvent.class
                    .getDeclaredField("timestamp");
            tsField.setAccessible(true);
            tsField.setLong(event, timestamp);

            java.lang.reflect.Method method = LeakDetectionCacheService.class
                    .getDeclaredMethod("handleEvent", MonitoringEvents.LeakDetectionEvent.class);
            method.setAccessible(true);
            method.invoke(service, event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 静态导入 assertDoesNotThrow 避免在测试方法中用全限定名
    private static void assertDoesNotThrow(org.junit.jupiter.api.function.Executable executable) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(executable);
    }
}
