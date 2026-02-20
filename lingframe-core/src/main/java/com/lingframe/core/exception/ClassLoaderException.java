package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 类加载器异常
 * 用于类加载器初始化、JAR加载等失败场景。
 */
public class ClassLoaderException extends LingException {

    private final String lingId;
    private final String resource;

    public ClassLoaderException(String message) {
        super(message);
        this.lingId = null;
        this.resource = null;
    }

    public ClassLoaderException(String message, Throwable cause) {
        super(message, cause);
        this.lingId = null;
        this.resource = null;
    }

    public ClassLoaderException(String lingId, String resource, String message) {
        super(message);
        this.lingId = lingId;
        this.resource = resource;
    }

    public ClassLoaderException(String lingId, String resource, String message, Throwable cause) {
        super(message, cause);
        this.lingId = lingId;
        this.resource = resource;
    }

    public String getLingId() {
        return lingId;
    }

    public String getResource() {
        return resource;
    }
}
