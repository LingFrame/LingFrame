package com.lingframe.api.exception;

import com.lingframe.api.security.AccessType;

/**
 * 权限拒绝异常
 * 当插件尝试执行未经授权的操作时抛出此异常。
 * 
 * @author LingFrame
 */
public class PermissionDeniedException extends LingException {

    private String pluginId;
    private String capability;
    private AccessType accessType;

    public PermissionDeniedException(String message) {
        super(message);
    }

    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }

    public PermissionDeniedException(String pluginId, String capability) {
        super("Access denied: plugin=" + pluginId + ", capability=" + capability);
        this.pluginId = pluginId;
        this.capability = capability;
    }

    public PermissionDeniedException(String pluginId, String capability, AccessType accessType) {
        super(String.format("Access denied: plugin=%s, capability=%s, accessType=%s",
                pluginId, capability, accessType));
        this.pluginId = pluginId;
        this.capability = capability;
        this.accessType = accessType;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getCapability() {
        return capability;
    }

    public AccessType getAccessType() {
        return accessType;
    }
}
