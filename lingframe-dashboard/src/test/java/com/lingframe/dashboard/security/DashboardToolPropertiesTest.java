package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Dashboard 演练能力开关测试")
class DashboardToolPropertiesTest {

    @Test
    @DisplayName("生产默认关闭所有高风险演练能力")
    void productionDefaultsShouldBeClosed() {
        DashboardToolProperties properties = new DashboardToolProperties();

        assertFalse(properties.isRealInvocationEnabled());
        assertFalse(properties.isSimulationEnabled());
        assertFalse(properties.isStressTestEnabled());
        assertFalse(properties.isModeSwitchEnabled());
    }

    @Test
    @DisplayName("直接构造控制器的兼容配置保留旧行为")
    void directUseDefaultsShouldEnableLegacyBehavior() {
        DashboardToolProperties properties = DashboardToolProperties.directUseDefaults();

        assertTrue(properties.isRealInvocationEnabled());
        assertTrue(properties.isSimulationEnabled());
        assertTrue(properties.isStressTestEnabled());
        assertTrue(properties.isModeSwitchEnabled());
    }
}
