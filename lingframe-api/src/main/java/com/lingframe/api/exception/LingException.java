package com.lingframe.api.exception;

/**
 * 灵珑基础异常
 * 
 * @author LingFrame
 */
public class LingException extends RuntimeException {
    
    public LingException(String message) {
        super(message);
    }

    public LingException(String message, Throwable cause) {
        super(message, cause);
    }
}
