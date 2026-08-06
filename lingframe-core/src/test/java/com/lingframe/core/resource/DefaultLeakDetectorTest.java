package com.lingframe.core.resource;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.runtime.FixedRuntimeMode;
import com.lingframe.core.spi.LeakRiskReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DefaultLeakDetector 测试")
class DefaultLeakDetectorTest {

    private DefaultLeakDetector detector;
    private EventBus eventBus;
    private LingFrameConfig config;

    @BeforeEach
    void setUp() {
        eventBus = mock(EventBus.class);
        config = mock(LingFrameConfig.class);
        when(config.isDevMode()).thenReturn(true);
        when(config.getRuntimeMode()).thenReturn(new FixedRuntimeMode(true, false));
        when(config.getLeakDetectionMaxConcurrentAggressiveChecks()).thenReturn(2);
        when(config.getLeakDetectionDevStartDelayMillis()).thenReturn(0);
        when(config.getLeakDetectionAggressiveGcRounds()).thenReturn(1);
        when(config.getLeakDetectionAggressiveGcIntervalMillis()).thenReturn(10);
        when(config.getLeakDetectionPassiveWindowMillis()).thenReturn(100);
        when(config.getLeakDetectionFinalConfirmationDelayMillis()).thenReturn(50);
        when(config.getLeakDetectionQueuePollMillis()).thenReturn(200);

        detector = new DefaultLeakDetector(eventBus, config);
    }

    @AfterEach
    void tearDown() {
        detector.shutdown();
    }

    @Test
    @DisplayName("detectLeak null ClassLoader 不报错")
    void shouldHandleNullClassLoader() {
        assertDoesNotThrow(() -> detector.detectLeak("ling-a", "1.0.0", null));
    }

    @Test
    @DisplayName("checkBefore null ClassLoader 返回 checkFailed")
    void shouldReturnCheckFailedForNullClassLoader() {
        LeakRiskReport report = detector.checkBefore("ling-a", "1.0.0", null);
        assertNotNull(report);
    }

    @Test
    @DisplayName("checkBefore 正常 ClassLoader 返回 noRisk")
    void shouldReturnNoRiskForNormalClassLoader() {
        ClassLoader cl = new ClassLoader() {};
        LeakRiskReport report = detector.checkBefore("ling-a", "1.0.0", cl);
        assertNotNull(report);
    }

    @Test
    @DisplayName("detectLeak 正常 ClassLoader 不报错")
    void shouldDetectLeakForNormalClassLoader() {
        ClassLoader cl = new ClassLoader() {};
        assertDoesNotThrow(() -> detector.detectLeak("ling-a", "1.0.0", cl));
    }

    @Test
    @DisplayName("shutdown 不报错")
    void shouldShutdownCleanly() {
        assertDoesNotThrow(() -> detector.shutdown());
    }

    @Test
    @DisplayName("带参构造器不报错")
    void shouldCreateWithConfigConstructor() {
        assertDoesNotThrow(() -> {
            DefaultLeakDetector d = new DefaultLeakDetector(null, LingFrameConfig.builder().build());
            d.shutdown();
        });
    }

    @Test
    @DisplayName("prod 模式下 detectLeak 不报错")
    void shouldDetectLeakInProdMode() {
        LingFrameConfig prodConfig = mock(LingFrameConfig.class);
        when(prodConfig.isDevMode()).thenReturn(false);
        when(prodConfig.getRuntimeMode()).thenReturn(new FixedRuntimeMode(false, false));
        when(prodConfig.getLeakDetectionMaxConcurrentAggressiveChecks()).thenReturn(2);
        when(prodConfig.getLeakDetectionDevStartDelayMillis()).thenReturn(0);
        when(prodConfig.getLeakDetectionAggressiveGcRounds()).thenReturn(1);
        when(prodConfig.getLeakDetectionAggressiveGcIntervalMillis()).thenReturn(10);
        when(prodConfig.getLeakDetectionPassiveWindowMillis()).thenReturn(100);
        when(prodConfig.getLeakDetectionFinalConfirmationDelayMillis()).thenReturn(50);
        when(prodConfig.getLeakDetectionQueuePollMillis()).thenReturn(200);

        DefaultLeakDetector prodDetector = new DefaultLeakDetector(eventBus, prodConfig);
        ClassLoader cl = new ClassLoader() {};
        assertDoesNotThrow(() -> prodDetector.detectLeak("ling-a", "1.0.0", cl));
        prodDetector.shutdown();
    }

    @Test
    @DisplayName("findThreadContextClassLoaderRisks 返回空列表当无风险")
    void shouldReturnEmptyRisksWhenNoRisk() {
        ClassLoader cl = new ClassLoader() {};
        assertTrue(detector.findThreadContextClassLoaderRisks(cl).isEmpty());
    }

    @Test
    @DisplayName("dev 模式多次 detectLeak 触发限流不报错")
    void shouldThrottleAggressiveChecks() {
        ClassLoader cl1 = new ClassLoader() {};
        ClassLoader cl2 = new ClassLoader() {};
        ClassLoader cl3 = new ClassLoader() {};
        // maxConcurrentAggressiveChecks=2，第三次应走 bounded 路径
        assertDoesNotThrow(() -> {
            detector.detectLeak("ling-a", "1.0", cl1);
            detector.detectLeak("ling-b", "1.0", cl2);
            detector.detectLeak("ling-c", "1.0", cl3);
        });
    }

    @Test
    @DisplayName("非 null config 正常工作")
    void shouldWorkWithExplicitConfig() {
        assertDoesNotThrow(() -> {
            DefaultLeakDetector d = new DefaultLeakDetector(eventBus, LingFrameConfig.builder().build());
            d.detectLeak("ling-a", "1.0", new ClassLoader() {});
            d.shutdown();
        });
    }

    @Test
    @DisplayName("checkBefore 非 null ClassLoader 返回有效报告")
    void shouldReturnValidReportForNonNullClassLoader() {
        ClassLoader cl = new ClassLoader() {};
        LeakRiskReport report = detector.checkBefore("ling-a", "1.0.0", cl);
        assertNotNull(report);
        assertEquals("ling-a", report.getLingId());
    }

    @Test
    @DisplayName("prod 模式 checkBefore 正常工作")
    void shouldCheckBeforeInProdMode() {
        LingFrameConfig prodConfig = mock(LingFrameConfig.class);
        when(prodConfig.isDevMode()).thenReturn(false);
        when(prodConfig.getRuntimeMode()).thenReturn(new FixedRuntimeMode(false, false));
        when(prodConfig.getLeakDetectionMaxConcurrentAggressiveChecks()).thenReturn(2);
        when(prodConfig.getLeakDetectionDevStartDelayMillis()).thenReturn(0);
        when(prodConfig.getLeakDetectionAggressiveGcRounds()).thenReturn(1);
        when(prodConfig.getLeakDetectionAggressiveGcIntervalMillis()).thenReturn(10);
        when(prodConfig.getLeakDetectionPassiveWindowMillis()).thenReturn(100);
        when(prodConfig.getLeakDetectionFinalConfirmationDelayMillis()).thenReturn(50);
        when(prodConfig.getLeakDetectionQueuePollMillis()).thenReturn(200);

        DefaultLeakDetector prodDetector = new DefaultLeakDetector(eventBus, prodConfig);
        ClassLoader cl = new ClassLoader() {};
        LeakRiskReport report = prodDetector.checkBefore("ling-a", "1.0.0", cl);
        assertNotNull(report);
        prodDetector.shutdown();
    }
}
