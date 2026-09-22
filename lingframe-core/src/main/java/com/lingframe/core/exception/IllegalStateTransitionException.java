package com.lingframe.core.exception;

import com.lingframe.api.exception.LingException;

/**
 * 状态机非法转换异常
 * <p>
 * 继承 LingException，确保 catch(LingException) 能统一捕获框架异常。
 */
public class IllegalStateTransitionException extends LingException {
    public IllegalStateTransitionException(Enum<?> from, Enum<?> to) {
        super("Illegal transition: " + from + " → " + to);
    }
}
