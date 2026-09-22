package com.lingframe.core.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("弹性治理总开关测试")
class ResilienceGovernanceSwitchTest {

    @Test
    @DisplayName("默认开启且可关闭后恢复")
    void shouldToggleResilienceGovernance() {
        ResilienceGovernanceSwitch governanceSwitch = new ResilienceGovernanceSwitch();

        assertTrue(governanceSwitch.isEnabled());
        governanceSwitch.setEnabled(false);
        assertFalse(governanceSwitch.isEnabled());
        governanceSwitch.setEnabled(true);
        assertTrue(governanceSwitch.isEnabled());
    }
}
