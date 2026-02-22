package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 单元运行时异常
 * <p>
 * 用于单元执行过程中发生的错误
 */
public class LingRuntimeException extends LingException {

    private final String lingId;

    public LingRuntimeException(String lingId, String message) {
        super(message);
        this.lingId = lingId;
    }

    public LingRuntimeException(String lingId, String message, Throwable cause) {
        super(message, cause);
        this.lingId = lingId;
    }

    public String getLingId() {
        return lingId;
    }
}
