package com.lingframe.core.ling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LingRuntimeConfig 测试")
public class LingRuntimeConfigTest {

    @Nested
    @DisplayName("默认配置")
    class DefaultConfigTests {

        @Test
        @DisplayName("defaults() 应返回合理的默认值")
        void defaultsShouldReturnReasonableValues() {
            LingRuntimeConfig config = LingRuntimeConfig.defaults();

            assertEquals(5, config.getMaxHistorySnapshots());
            assertEquals(30, config.getForceCleanupDelaySeconds());
            assertEquals(5, config.getDyingCheckIntervalSeconds());
            assertEquals(3000, config.getDefaultTimeoutMs());
            assertEquals(10, config.getBulkheadMaxConcurrent());
            assertEquals(3000, config.getBulkheadAcquireTimeoutMs());
        }

        @Test
        @DisplayName("Builder 默认值应与 defaults() 相同")
        void builderDefaultsShouldMatchDefaults() {
            LingRuntimeConfig fromBuilder = LingRuntimeConfig.builder().build();
            LingRuntimeConfig fromDefaults = LingRuntimeConfig.defaults();

            assertEquals(fromDefaults.getMaxHistorySnapshots(), fromBuilder.getMaxHistorySnapshots());
            assertEquals(fromDefaults.getDefaultTimeoutMs(), fromBuilder.getDefaultTimeoutMs());
            assertEquals(fromDefaults.getBulkheadMaxConcurrent(), fromBuilder.getBulkheadMaxConcurrent());
        }
    }

    @Nested
    @DisplayName("自定义配置")
    class CustomConfigTests {

        @Test
        @DisplayName("Builder 应支持自定义值")
        void builderShouldSupportCustomValues() {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .maxHistorySnapshots(20)
                    .defaultTimeoutMs(10000)
                    .bulkheadMaxConcurrent(100)
                    .forceCleanupDelaySeconds(60)
                    .dyingCheckIntervalSeconds(10)
                    .bulkheadAcquireTimeoutMs(5000)
                    .build();

            assertEquals(20, config.getMaxHistorySnapshots());
            assertEquals(10000, config.getDefaultTimeoutMs());
            assertEquals(100, config.getBulkheadMaxConcurrent());
            assertEquals(60, config.getForceCleanupDelaySeconds());
            assertEquals(10, config.getDyingCheckIntervalSeconds());
            assertEquals(5000, config.getBulkheadAcquireTimeoutMs());
        }

        @Test
        @DisplayName("可以只覆盖部分值")
        void canOverridePartialValues() {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .defaultTimeoutMs(5000)
                    .build();

            // 覆盖的值
            assertEquals(5000, config.getDefaultTimeoutMs());

            // 其他保持默认
            assertEquals(5, config.getMaxHistorySnapshots());
            assertEquals(10, config.getBulkheadMaxConcurrent());
        }
    }

    @Nested
    @DisplayName("字符串表示")
    class ToStringTests {

        @Test
        @DisplayName("toString 应包含关键信息")
        void toStringShouldContainKeyInfo() {
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .maxHistorySnapshots(10)
                    .defaultTimeoutMs(5000)
                    .bulkheadMaxConcurrent(50)
                    .build();

            String str = config.toString();

            assertTrue(str.contains("maxHistory=10"));
            assertTrue(str.contains("timeout=5000ms"));
            assertTrue(str.contains("bulkhead=50"));
        }
    }
}
