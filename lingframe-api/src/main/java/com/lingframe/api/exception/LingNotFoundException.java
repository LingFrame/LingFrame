package com.lingframe.api.exception;

/**
 * 灵元未找到异常
 * 当请求的灵元不存在时抛出此异常。
 */
public class LingNotFoundException extends LingException {

    private final String lingId;

    public LingNotFoundException(String lingId) {
        super("Ling not found: " + lingId);
        this.lingId = lingId;
    }

    public LingNotFoundException(String lingId, String message) {
        super(message);
        this.lingId = lingId;
    }

    public String getLingId() {
        return lingId;
    }
}
