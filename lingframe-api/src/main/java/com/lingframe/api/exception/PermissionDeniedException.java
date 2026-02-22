package com.lingframe.api.exception;

import com.lingframe.api.security.AccessType;

/**
 * 权限拒绝异常
 * 当单元尝试执行未经授权的操作时抛出此异常。
 * 
 * @author LingFrame
 */
public class PermissionDeniedException extends LingException {

    private String lingId;
    private String capability;
    private AccessType accessType;

    public PermissionDeniedException(String message) {
        super(message);
    }

    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }

    public PermissionDeniedException(String lingId, String capability) {
        super("Access denied: ling=" + lingId + ", capability=" + capability);
        this.lingId = lingId;
        this.capability = capability;
    }

    public PermissionDeniedException(String lingId, String capability, AccessType accessType) {
        super(String.format("Access denied: ling=%s, capability=%s, accessType=%s",
                lingId, capability, accessType));
        this.lingId = lingId;
        this.capability = capability;
        this.accessType = accessType;
    }

    public String getLingId() {
        return lingId;
    }

    public String getCapability() {
        return capability;
    }

    public AccessType getAccessType() {
        return accessType;
    }
}
