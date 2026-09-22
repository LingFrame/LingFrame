package com.lingframe.core.routing;

import java.util.Objects;

/**
 * 单次调用最终路由事实。
 * <p>
 * 策略修订和选路原因来自 L0，实例代次来自 L1；对象创建后不可变，
 * 避免观测者读到版本与实例相互矛盾的中间状态。
 */
public final class RoutingDecision {
    private final String lingId;
    private final String version;
    private final String instanceId;
    private final String policyRevision;
    private final String reason;

    public RoutingDecision(String lingId, String version, String instanceId, String policyRevision, String reason) {
        this.lingId = Objects.requireNonNull(lingId, "lingId");
        this.version = Objects.requireNonNull(version, "version");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.policyRevision = policyRevision;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String getLingId() { return lingId; }
    public String getVersion() { return version; }
    public String getInstanceId() { return instanceId; }
    public String getPolicyRevision() { return policyRevision; }
    public String getReason() { return reason; }
}
