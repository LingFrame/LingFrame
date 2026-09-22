package com.lingframe.core.ling;

import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 卸载请求的结构化结果。
 */
public class LingUninstallResult {

    private final String lingId;
    private final String version;
    private final boolean uninstallTriggered;
    private final LeakRiskLevel overallRiskLevel;
    private final List<LeakRiskReport> reports;
    private final String operationId;
    private final boolean cleanupCompleted;
    private final boolean classLoaderGcConfirmed;

    public LingUninstallResult(String lingId, String version, boolean uninstallTriggered, List<LeakRiskReport> reports) {
        this(lingId, version, uninstallTriggered, reports,
                uninstallTriggered, false, UUID.randomUUID().toString());
    }

    public LingUninstallResult(String lingId, String version, boolean uninstallTriggered, List<LeakRiskReport> reports,
            boolean cleanupCompleted, boolean classLoaderGcConfirmed, String operationId) {
        this.lingId = lingId;
        this.version = version;
        this.uninstallTriggered = uninstallTriggered;
        this.reports = reports == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(reports));
        this.overallRiskLevel = aggregateRiskLevel(this.reports);
        this.cleanupCompleted = cleanupCompleted;
        this.classLoaderGcConfirmed = classLoaderGcConfirmed;
        this.operationId = operationId == null || operationId.trim().isEmpty()
                ? UUID.randomUUID().toString() : operationId;
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

    /** @return 本次卸载操作的稳定关联标识 */
    public String getOperationId() {
        return operationId;
    }

    /** @return 清理协调器是否已完成登记的清理阶段，不表示类加载器已被 GC */
    public boolean isCleanupCompleted() {
        return cleanupCompleted;
    }

    /** @return 是否有独立证据证明类加载器已被 GC；默认不作此承诺 */
    public boolean isClassLoaderGcConfirmed() {
        return classLoaderGcConfirmed;
    }
}
