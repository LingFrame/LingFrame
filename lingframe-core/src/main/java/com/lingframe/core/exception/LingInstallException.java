package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 单元安装异常
 */
public class LingInstallException extends LingException {

    private final String lingId;

    public LingInstallException(String lingId, String message) {
        super(message);
        this.lingId = lingId;
    }

    public LingInstallException(String lingId, String message, Throwable cause) {
        super(message, cause);
        this.lingId = lingId;
    }

    public String getLingId() {
        return lingId;
    }
}
