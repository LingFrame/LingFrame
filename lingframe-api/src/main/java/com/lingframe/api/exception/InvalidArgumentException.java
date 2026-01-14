package com.lingframe.api.exception;

/**
 * 无效参数异常
 * 当传入的参数不满足业务要求时抛出此异常。
 */
public class InvalidArgumentException extends LingException {

    private final String paramName;
    private final Object invalidValue;

    public InvalidArgumentException(String message) {
        super(message);
        this.paramName = null;
        this.invalidValue = null;
    }

    public InvalidArgumentException(String paramName, String message) {
        super(message);
        this.paramName = paramName;
        this.invalidValue = null;
    }

    public InvalidArgumentException(String paramName, Object invalidValue, String message) {
        super(message);
        this.paramName = paramName;
        this.invalidValue = invalidValue;
    }

    public String getParamName() {
        return paramName;
    }

    public Object getInvalidValue() {
        return invalidValue;
    }

    public InvalidArgumentException(String paramName, String message, Throwable cause) {
        super(message, cause);
        this.paramName = paramName;
        this.invalidValue = null;
    }
}
