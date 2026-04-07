package com.lingframe.core.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 卸载前 leak risk 预检结果。
 */
public class LeakRiskReport {

    private final String lingId;
    private final String version;
    private final LeakRiskLevel level;
    private final String summary;
    private final List<String> details;
    private final String checker;
    private final long timestamp;

    public LeakRiskReport(String lingId,
                          String version,
                          LeakRiskLevel level,
                          String summary,
                          List<String> details,
                          String checker,
                          long timestamp) {
        this.lingId = lingId;
        this.version = version;
        this.level = level == null ? LeakRiskLevel.NO_RISK : level;
        this.summary = summary;
        this.details = details == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(details));
        this.checker = checker;
        this.timestamp = timestamp;
    }

    public static LeakRiskReport noRisk(String lingId, String version, String summary, List<String> details,
                                        String checker) {
        return new LeakRiskReport(
                lingId,
                version,
                LeakRiskLevel.NO_RISK,
                summary,
                details,
                checker,
                System.currentTimeMillis());
    }

    public static LeakRiskReport riskDetected(String lingId, String version, String summary, List<String> details,
                                              String checker) {
        return new LeakRiskReport(
                lingId,
                version,
                LeakRiskLevel.RISK_DETECTED,
                summary,
                details,
                checker,
                System.currentTimeMillis());
    }

    public static LeakRiskReport checkFailed(String lingId, String version, String summary, List<String> details,
                                             String checker) {
        return new LeakRiskReport(
                lingId,
                version,
                LeakRiskLevel.CHECK_FAILED,
                summary,
                details,
                checker,
                System.currentTimeMillis());
    }

    public String getLingId() {
        return lingId;
    }

    public String getVersion() {
        return version;
    }

    public LeakRiskLevel getLevel() {
        return level;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getDetails() {
        return details;
    }

    public String getChecker() {
        return checker;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
