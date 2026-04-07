package com.lingframe.dashboard.service;

import com.lingframe.core.classloader.SharedApiClassLoader;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.dashboard.dto.ResourceCleanupCapabilityDTO;
import com.lingframe.dashboard.dto.RuntimeGovernanceReadinessDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RuntimeDiagnosticsService {

    private final EventBus eventBus;
    private final Map<String, ResourceCleanupCapabilityDTO> cleanupCapabilities = new ConcurrentHashMap<>();
    private volatile String lastReadinessStatus;
    public RuntimeDiagnosticsService(EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.ResourceCleanupCapabilityEvent.class,
                this::handleCleanupCapability);
    }

    public Map<String, ResourceCleanupCapabilityDTO> getCleanupCapabilities() {
        return new LinkedHashMap<>(cleanupCapabilities);
    }

    public RuntimeGovernanceReadinessDTO getGovernanceReadiness() {
        boolean sharedApiBoundaryFrozen = SharedApiClassLoader.isBoundaryFrozen();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!sharedApiBoundaryFrozen) {
            blockers.add("Shared API boundary is not frozen");
        }

        if (cleanupCapabilities.isEmpty()) {
            warnings.add("No resource cleanup capability snapshot has been observed yet");
        }

        for (ResourceCleanupCapabilityDTO capability : cleanupCapabilities.values()) {
            if (!capability.isThreadTargetAccessible()) {
                warnings.add(capability.getRuntime() + ": Thread.target cleanup is unavailable on this JVM");
            }
            if (!capability.isDriverManagerAccessible()) {
                warnings.add(capability.getRuntime() + ": DriverManager cleanup is unavailable on this JVM");
            }
        }

        String status;
        String summary;
        if (!blockers.isEmpty()) {
            status = "BLOCKED";
            summary = "Runtime governance is blocked by an unfrozen shared boundary.";
        } else if (!warnings.isEmpty()) {
            status = "LIMITED";
            summary = "Runtime governance is active, but some diagnostics indicate capability limits or missing observations.";
        } else {
            status = "READY";
            summary = "Runtime governance is aligned: shared boundary is frozen and cleanup capabilities are visible.";
        }

        return RuntimeGovernanceReadinessDTO.builder()
                .status(status)
                .summary(summary)
                .sharedApiBoundaryFrozen(sharedApiBoundaryFrozen)
                .diagnosticsCount(cleanupCapabilities.size())
                .blockers(blockers)
                .warnings(warnings)
                .build();
    }

    private void handleCleanupCapability(MonitoringEvents.ResourceCleanupCapabilityEvent event) {
        if (event == null || event.getRuntime() == null) {
            return;
        }
        cleanupCapabilities.put(event.getRuntime(), ResourceCleanupCapabilityDTO.builder()
                .runtime(event.getRuntime())
                .jdkVersion(event.getJdkVersion())
                .threadTargetAccessible(event.isThreadTargetAccessible())
                .threadAccessControlAccessible(event.isThreadAccessControlAccessible())
                .accessControlContextAccessible(event.isAccessControlContextAccessible())
                .virtualThreadIntrospectionAvailable(event.isVirtualThreadIntrospectionAvailable())
                .driverManagerAccessible(event.isDriverManagerAccessible())
                .summary(event.getSummary())
                .timestamp(event.getTimestamp())
                .build());
        publishReadinessAlertIfNeeded(getGovernanceReadiness());
    }

    private void publishReadinessAlertIfNeeded(RuntimeGovernanceReadinessDTO readiness) {
        if (readiness == null || readiness.getStatus() == null) {
            return;
        }

        String currentStatus = readiness.getStatus();
        String previousStatus = lastReadinessStatus;
        String currentSummary = readiness.getSummary();

        boolean shouldAlert = false;
        boolean recoveredToReady = false;
        if (previousStatus == null) {
            shouldAlert = !"READY".equals(currentStatus);
        } else {
            shouldAlert = readinessRank(currentStatus) > readinessRank(previousStatus);
            recoveredToReady = readinessRank(currentStatus) < readinessRank(previousStatus)
                    && "READY".equals(currentStatus);
        }

        lastReadinessStatus = currentStatus;
        if (!shouldAlert && !recoveredToReady) {
            return;
        }

        StringBuilder message = new StringBuilder(currentSummary == null ? currentStatus : currentSummary);
        if (readiness.getBlockers() != null && !readiness.getBlockers().isEmpty()) {
            message.append(" blockers=").append(readiness.getBlockers());
        }
        if (readiness.getWarnings() != null && !readiness.getWarnings().isEmpty()) {
            message.append(" warnings=").append(readiness.getWarnings());
        }

        eventBus.publish(new MonitoringEvents.AlertNotifyEvent(
                recoveredToReady ? "INFO" : "WARNING",
                "RUNTIME_GOVERNANCE_READINESS",
                "lingframe-runtime",
                message.toString()));
    }

    private int readinessRank(String status) {
        if ("BLOCKED".equals(status)) {
            return 2;
        }
        if ("LIMITED".equals(status)) {
            return 1;
        }
        return 0;
    }
}
