package com.lingframe.core.ling;

import com.lingframe.core.fsm.InstanceStatus;

import java.util.Objects;

/**
 * 实例代次的只读运行事实快照。
 * <p>
 * 快照不授予生命周期或接流状态写权限；清理是否完成仍由卸载协调器单独表达。
 */
public final class LingInstanceSnapshot {
    private final String instanceId;
    private final String lingId;
    private final String version;
    private final InstanceStatus status;
    private final boolean defaultInstance;
    private final boolean admissionDisabled;
    private final long activeRequestCount;

    public LingInstanceSnapshot(String instanceId, String lingId, String version, InstanceStatus status,
            boolean defaultInstance, boolean admissionDisabled, long activeRequestCount) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.lingId = Objects.requireNonNull(lingId, "lingId");
        this.version = Objects.requireNonNull(version, "version");
        this.status = Objects.requireNonNull(status, "status");
        if (activeRequestCount < 0) {
            throw new IllegalArgumentException("activeRequestCount must not be negative");
        }
        this.defaultInstance = defaultInstance;
        this.admissionDisabled = admissionDisabled;
        this.activeRequestCount = activeRequestCount;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getLingId() {
        return lingId;
    }

    public String getVersion() {
        return version;
    }

    public InstanceStatus getStatus() {
        return status;
    }

    public boolean isDefaultInstance() {
        return defaultInstance;
    }

    public boolean isAdmissionDisabled() {
        return admissionDisabled;
    }

    public long getActiveRequestCount() {
        return activeRequestCount;
    }

    public boolean isReady() {
        return status == InstanceStatus.READY;
    }

    public boolean isDraining() {
        return status == InstanceStatus.STOPPING;
    }
}
