package com.lingframe.api.exception;

/**
 * 权限拒绝异常
 * 当插件尝试执行未经授权的操作时抛出此异常。
 * 
 * @author LingFrame
 */
public class PermissionDeniedException extends LingException {

    public PermissionDeniedException(String message) {
        super(message);
    }

    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
