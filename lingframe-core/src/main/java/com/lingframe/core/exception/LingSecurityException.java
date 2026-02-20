package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 单元安全异常
 * <p>
 * 用于单元安全验证失败的场景，如危险 API 检测
 */
public class LingSecurityException extends LingException {

    private final String lingId;

    public LingSecurityException(String lingId, String message) {
        super(message);
        this.lingId = lingId;
    }

    public LingSecurityException(String lingId, String message, Throwable cause) {
        super(message, cause);
        this.lingId = lingId;
    }

    public String getLingId() {
        return lingId;
    }
}
