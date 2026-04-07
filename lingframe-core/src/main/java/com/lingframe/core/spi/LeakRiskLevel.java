package com.lingframe.core.spi;

/**
 * 卸载前 leak risk 预检的风险等级。
 */
public enum LeakRiskLevel {
    NO_RISK(0),
    CHECK_FAILED(1),
    RISK_DETECTED(2);

    private final int severity;

    LeakRiskLevel(int severity) {
        this.severity = severity;
    }

    public static LeakRiskLevel max(LeakRiskLevel left, LeakRiskLevel right) {
        LeakRiskLevel normalizedLeft = left == null ? NO_RISK : left;
        LeakRiskLevel normalizedRight = right == null ? NO_RISK : right;
        return normalizedLeft.severity >= normalizedRight.severity ? normalizedLeft : normalizedRight;
    }
}
