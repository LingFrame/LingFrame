package com.lingframe.core.resource;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DefaultLeakDetector 测试")
class DefaultLeakDetectorTest {

    @Test
    @DisplayName("开发激进模式下应发出确认失败事件")
    void devAggressiveModeEmitsConfirmedFailureEvent() throws Exception {
        EventBus eventBus = new EventBus();
        LingFrameConfig config = LingFrameConfig.builder()
                .devMode(true)
                .leakDetectionMaxConcurrentAggressiveChecks(1)
                .leakDetectionDevStartDelayMillis(10)
                .leakDetectionAggressiveGcRounds(1)
                .leakDetectionAggressiveGcIntervalMillis(10)
                .leakDetectionFinalConfirmationDelayMillis(10)
                .build();
        DefaultLeakDetector detector = new DefaultLeakDetector(eventBus, config);

        try {
            AtomicReference<MonitoringEvents.LeakDetectionEvent> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe("test", MonitoringEvents.LeakDetectionEvent.class, event -> {
                if ("v1".equals(event.getVersion())) {
                    captured.set(event);
                    latch.countDown();
                }
            });

            ClassLoader held = new ClassLoader() {
            };
            detector.detectLeak("ling-a", "v1", held);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            MonitoringEvents.LeakDetectionEvent event = captured.get();
            assertNotNull(event);
            assertFalse(event.isCollected());
            assertEquals(DefaultLeakDetector.MODE_DEV_AGGRESSIVE, event.getDetectionMode());
            assertTrue(event.getTriggerTimeMillis() > 0L);
        } finally {
            detector.shutdown();
        }
    }

    @Test
    @DisplayName("开发模式下激进检测饱和时应回退到有界确认")
    void devModeFallsBackWhenAggressiveChecksAreSaturated() throws Exception {
        EventBus eventBus = new EventBus();
        LingFrameConfig config = LingFrameConfig.builder()
                .devMode(true)
                .leakDetectionMaxConcurrentAggressiveChecks(1)
                .leakDetectionDevStartDelayMillis(20)
                .leakDetectionAggressiveGcRounds(2)
                .leakDetectionAggressiveGcIntervalMillis(20)
                .leakDetectionFinalConfirmationDelayMillis(20)
                .build();
        DefaultLeakDetector detector = new DefaultLeakDetector(eventBus, config);

        try {
            AtomicReference<MonitoringEvents.LeakDetectionEvent> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe("test", MonitoringEvents.LeakDetectionEvent.class, event -> {
                if ("v2".equals(event.getVersion())) {
                    captured.set(event);
                    latch.countDown();
                }
            });

            ClassLoader firstHeld = new ClassLoader() {
            };
            ClassLoader secondHeld = new ClassLoader() {
            };
            detector.detectLeak("ling-a", "v1", firstHeld);
            detector.detectLeak("ling-a", "v2", secondHeld);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            MonitoringEvents.LeakDetectionEvent event = captured.get();
            assertNotNull(event);
            assertFalse(event.isCollected());
            assertEquals(DefaultLeakDetector.MODE_DEV_BOUNDED, event.getDetectionMode());
        } finally {
            detector.shutdown();
        }
    }

    @Test
    @DisplayName("生产被动模式下应发出被动窗口失败事件")
    void prodPassiveModeEmitsFailureEvent() throws Exception {
        EventBus eventBus = new EventBus();
        LingFrameConfig config = LingFrameConfig.builder()
                .devMode(false)
                .leakDetectionPassiveWindowMillis(20)
                .leakDetectionQueuePollMillis(10)
                .build();
        DefaultLeakDetector detector = new DefaultLeakDetector(eventBus, config);

        try {
            AtomicReference<MonitoringEvents.LeakDetectionEvent> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            eventBus.subscribe("test", MonitoringEvents.LeakDetectionEvent.class, event -> {
                if ("v3".equals(event.getVersion())) {
                    captured.set(event);
                    latch.countDown();
                }
            });

            ClassLoader held = new ClassLoader() {
            };
            detector.detectLeak("ling-a", "v3", held);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            MonitoringEvents.LeakDetectionEvent event = captured.get();
            assertNotNull(event);
            assertFalse(event.isCollected());
            assertEquals(DefaultLeakDetector.MODE_PROD_PASSIVE, event.getDetectionMode());
            assertTrue(event.getMessage().contains("passive window"));
        } finally {
            detector.shutdown();
        }
    }
}
