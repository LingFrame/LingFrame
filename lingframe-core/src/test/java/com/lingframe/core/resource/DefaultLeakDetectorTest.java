package com.lingframe.core.resource;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
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

    @Test
    @DisplayName("卸载前预检在无明显信号时应返回无风险")
    void checkBeforeShouldReturnNoRiskWhenNoObviousSignalExists() {
        DefaultLeakDetector detector = new DefaultLeakDetector(new EventBus(), LingFrameConfig.builder().build());
        try {
            LeakRiskReport report = detector.checkBefore("ling-a", "v1", new ClassLoader() {
            });

            assertNotNull(report);
            assertEquals(LeakRiskLevel.NO_RISK, report.getLevel());
            assertTrue(report.getDetails().isEmpty());
        } finally {
            detector.shutdown();
        }
    }

    @Test
    @DisplayName("卸载前预检在检测到 TCCL 指向目标加载器时应返回风险")
    void checkBeforeShouldReturnRiskDetectedWhenTcclStillPointsToTargetClassLoader() throws Exception {
        DefaultLeakDetector detector = new DefaultLeakDetector(new EventBus(), LingFrameConfig.builder().build());
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClassLoader target = new ClassLoader() {
        };
        Thread worker = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(target);
            ready.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "leak-risk-precheck-worker");
        worker.start();

        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS));

            LeakRiskReport report = detector.checkBefore("ling-a", "v2", target);

            assertNotNull(report);
            assertEquals(LeakRiskLevel.RISK_DETECTED, report.getLevel());
            assertFalse(report.getDetails().isEmpty());
            assertTrue(report.getDetails().get(0).contains("leak-risk-precheck-worker"));
        } finally {
            release.countDown();
            worker.join(2000);
            detector.shutdown();
        }
    }

    @Test
    @DisplayName("卸载前预检异常时应降级为检查失败")
    void checkBeforeShouldReturnCheckFailedWhenPrecheckThrows() {
        DefaultLeakDetector detector = new DefaultLeakDetector(new EventBus(), LingFrameConfig.builder().build()) {
            @Override
            List<String> findThreadContextClassLoaderRisks(ClassLoader classLoader) {
                throw new IllegalStateException("boom");
            }
        };

        try {
            LeakRiskReport report = detector.checkBefore("ling-a", "v3", new ClassLoader() {
            });

            assertNotNull(report);
            assertEquals(LeakRiskLevel.CHECK_FAILED, report.getLevel());
            assertEquals(Collections.singletonList(IllegalStateException.class.getName()), report.getDetails());
        } finally {
            detector.shutdown();
        }
    }
}
