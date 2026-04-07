package com.lingframe.core.ling;

import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 卸载请求的结构化结果。
 */
public class LingUninstallResult {

    private final String lingId;
    private final String version;
    private final boolean uninstallTriggered;
    private final LeakRiskLevel overallRiskLevel;
    private final List<LeakRiskReport> reports;

    public LingUninstallResult(String lingId, String version, boolean uninstallTriggered, List<LeakRiskReport> reports) {
        this.lingId = lingId;
        this.version = version;
        this.uninstallTriggered = uninstallTriggered;
        this.reports = reports == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(reports));
        this.overallRiskLevel = aggregateRiskLevel(this.reports);
    }

    public static LingUninstallResult triggered(String lingId, String version, List<LeakRiskReport> reports) {
        return new LingUninstallResult(lingId, version, true, reports);
    }

    public static LingUninstallResult notTriggered(String lingId, String version, List<LeakRiskReport> reports) {
        return new LingUninstallResult(lingId, version, false, reports);
    }

    private LeakRiskLevel aggregateRiskLevel(List<LeakRiskReport> reports) {
        LeakRiskLevel level = LeakRiskLevel.NO_RISK;
        if (reports == null) {
            return level;
        }
        for (LeakRiskReport report : reports) {
            if (report != null) {
                level = LeakRiskLevel.max(level, report.getLevel());
            }
        }
        return level;
    }

    public String getLingId() {
        return lingId;
    }

    public String getVersion() {
        return version;
    }

    public boolean isUninstallTriggered() {
        return uninstallTriggered;
    }

    public LeakRiskLevel getOverallRiskLevel() {
        return overallRiskLevel;
    }

    public List<LeakRiskReport> getReports() {
        return reports;
    }
}
