package com.lingframe.core.fsm;

public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(Enum<?> from, Enum<?> to) {
        super("Illegal transition: " + from + " → " + to);
    }
}
