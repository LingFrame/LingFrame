package com.lingframe.api.exception;

/**
 * 当熔断器打开或限流器拒绝时抛出
 * <p>
 * 继承 LingException，确保 catch(LingException) 能统一捕获框架异常。
 */
public class CallNotPermittedException extends LingException {

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
