package com.lingframe.api.exception;

/**
 * LingFrame 基础异常
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
