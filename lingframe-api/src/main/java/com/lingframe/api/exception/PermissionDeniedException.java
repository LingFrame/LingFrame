package com.lingframe.api.exception;

import com.lingframe.api.security.AccessType;

/**
 * 权限拒绝异常
 * 当灵元尝试执行未经授权的操作时抛出此异常。
 * <p>
 * 作为运行时权限拒绝，继承 {@link LingRuntimeException}，复用其 lingId 字段，
 * 使异常层级与"运行时治理拒绝"语义对齐。
 *
 * @author LingFrame
 */
public class PermissionDeniedException extends LingRuntimeException {

    private String capability;
    private AccessType accessType;

    public PermissionDeniedException(String message) {
        super(null, message);
    }

    public PermissionDeniedException(String message, Throwable cause) {
        super(null, message, cause);
    }

    public PermissionDeniedException(String lingId, String capability) {
        super(lingId, "Access denied: ling=" + lingId + ", capability=" + capability);
        this.capability = capability;
    }

    public PermissionDeniedException(String lingId, String capability, AccessType accessType) {
        super(lingId, String.format("Access denied: ling=%s, capability=%s, accessType=%s",
                lingId, capability, accessType));
        this.capability = capability;
        this.accessType = accessType;
    }

    public String getCapability() {
        return capability;
    }

    public AccessType getAccessType() {
        return accessType;
    }
}
