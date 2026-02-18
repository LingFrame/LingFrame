package com.lingframe.core.exception;

/**
 * 当熔断器打开或限流器拒绝时抛出
 */
public class CallNotPermittedException extends RuntimeException {

    private final String resourceId;
    private final String reason;

    public CallNotPermittedException(String resourceId, String reason) {
        super("Call not permitted for " + resourceId + ": " + reason);
        this.resourceId = resourceId;
        this.reason = reason;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getReason() {
        return reason;
    }
}
