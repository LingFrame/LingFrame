package com.lingframe.core.config;

import com.lingframe.core.ling.LingRuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LingFrameConfig} 的补充测试。
 * <p>
 * 重点覆盖全局静态状态相关方法（current/init/isInitialized/clear）、
 * Builder 默认值与自定义值。每个涉及静态 INSTANCE 的测试均在结尾调用 clear() 还原状态。
 */
@DisplayName("LingFrameConfig 补充测试")
class LingFrameConfigSupplementTest {

    @AfterEach
    void clearGlobalState() {
        // 还原全局静态状态，避免污染其他测试
        LingFrameConfig.clear();
    }

    @Test
    @DisplayName("current 未初始化时应返回默认配置单例")
    void shouldReturnDefaultConfigWhenUninitialized() {
        assertFalse(LingFrameConfig.isInitialized());
        LingFrameConfig config = LingFrameConfig.current();
        assertNotNull(config);
        // 默认配置应为同一实例（懒加载单例）
        assertSame(config, LingFrameConfig.current());
    }

    @Test
    @DisplayName("init 后 isInitialized 应返回 true，current 应返回该实例")
    void shouldInitializeAndQueryInstance() {
        LingFrameConfig custom = LingFrameConfig.builder()
                .devMode(true)
                .lingHome("custom-home")
                .build();
        LingFrameConfig.init(custom);
        assertTrue(LingFrameConfig.isInitialized());
        assertSame(custom, LingFrameConfig.current());
        assertTrue(LingFrameConfig.current().isDevMode());
        assertEquals("custom-home", LingFrameConfig.current().getLingHome());
    }

    @Test
    @DisplayName("重复 init 应抛 IllegalStateException")
    void shouldThrowOnDoubleInit() {
        LingFrameConfig first = LingFrameConfig.builder().lingHome("first").build();
        LingFrameConfig.init(first);
        LingFrameConfig second = LingFrameConfig.builder().lingHome("second").build();
        assertThrows(IllegalStateException.class, () -> LingFrameConfig.init(second));
        // 第一个实例仍然有效
        assertSame(first, LingFrameConfig.current());
    }

    @Test
    @DisplayName("clear 后 isInitialized 应返回 false，current 回到默认配置")
    void shouldResetAfterClear() {
        LingFrameConfig custom = LingFrameConfig.builder().devMode(true).build();
        LingFrameConfig.init(custom);
        assertTrue(LingFrameConfig.isInitialized());
        LingFrameConfig.clear();
        assertFalse(LingFrameConfig.isInitialized());
        // clear 后 current 返回默认配置
        LingFrameConfig current = LingFrameConfig.current();
        assertFalse(current.isDevMode());
    }

    @Test
    @DisplayName("Builder 默认值应符合约定")
    void shouldApplyBuilderDefaults() {
        LingFrameConfig config = LingFrameConfig.builder().build();
        assertFalse(config.isDevMode());
        assertTrue(config.isAutoScan());
        assertEquals("Lings", config.getLingHome());
        assertTrue(config.getLingRoots().isEmpty());
        assertTrue(config.getPreloadApiJars().isEmpty());
        assertTrue(config.isApiOverrideCheckEnabled());
        assertFalse(config.isLingCoreGovernanceEnabled());
        assertFalse(config.isLingCoreGovernanceInternalCalls());
        assertFalse(config.isLingCoreCheckPermissions());
        assertEquals(8, config.getMaxThreadsPerLing());
        assertEquals(2, config.getDefaultThreadsPerLing());
        // corePoolSize 至少为 2
        assertTrue(config.getCorePoolSize() >= 2);
        // globalMaxLingThreads 为处理器数 4 倍
        assertEquals(Runtime.getRuntime().availableProcessors() * 4, config.getGlobalMaxLingThreads());
        // 泄漏检测相关默认值
        assertEquals(2, config.getLeakDetectionMaxConcurrentAggressiveChecks());
        assertEquals(2000, config.getLeakDetectionDevStartDelayMillis());
        assertEquals(5, config.getLeakDetectionAggressiveGcRounds());
        assertEquals(500, config.getLeakDetectionAggressiveGcIntervalMillis());
        assertEquals(60000, config.getLeakDetectionPassiveWindowMillis());
        assertEquals(1000, config.getLeakDetectionFinalConfirmationDelayMillis());
        assertEquals(5000, config.getLeakDetectionQueuePollMillis());
        // runtimeConfig 默认非空
        assertNotNull(config.getRuntimeConfig());
    }

    @Test
    @DisplayName("Builder 自定义值应正确赋值")
    void shouldApplyCustomValues() {
        List<String> roots = Arrays.asList("dir1", "dir2");
        List<String> jars = Arrays.asList("api1.jar", "api2.jar");
        LingRuntimeConfig runtimeConfig = LingRuntimeConfig.defaults();

        LingFrameConfig config = LingFrameConfig.builder()
                .devMode(true)
                .autoScan(false)
                .lingHome("/tmp/ling-home")
                .lingRoots(roots)
                .corePoolSize(16)
                .globalMaxLingThreads(100)
                .maxThreadsPerLing(32)
                .defaultThreadsPerLing(4)
                .lingCoreGovernanceEnabled(true)
                .lingCoreGovernanceInternalCalls(true)
                .lingCoreCheckPermissions(true)
                .preloadApiJars(jars)
                .apiOverrideCheckEnabled(false)
                .runtimeConfig(runtimeConfig)
                .build();

        assertTrue(config.isDevMode());
        assertFalse(config.isAutoScan());
        assertEquals("/tmp/ling-home", config.getLingHome());
        assertEquals(roots, config.getLingRoots());
        assertEquals(16, config.getCorePoolSize());
        assertEquals(100, config.getGlobalMaxLingThreads());
        assertEquals(32, config.getMaxThreadsPerLing());
        assertEquals(4, config.getDefaultThreadsPerLing());
        assertTrue(config.isLingCoreGovernanceEnabled());
        assertTrue(config.isLingCoreGovernanceInternalCalls());
        assertTrue(config.isLingCoreCheckPermissions());
        assertEquals(jars, config.getPreloadApiJars());
        assertFalse(config.isApiOverrideCheckEnabled());
        assertSame(runtimeConfig, config.getRuntimeConfig());
    }

    @Test
    @DisplayName("toString 应包含关键字段")
    void shouldProduceToString() {
        LingFrameConfig config = LingFrameConfig.builder()
                .devMode(true)
                .lingHome("home-test")
                .build();
        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("runtimeMode"));
        assertTrue(str.contains("home-test"));
    }
}
