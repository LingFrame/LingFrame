package com.lingframe.core.fsm;

import com.lingframe.core.exception.IllegalStateTransitionException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class StateMachine<S extends Enum<S>> {
    private final AtomicReference<S> current;
    private final Map<S, Set<S>> legalTransitions;

    public StateMachine(S initial, Map<S, Set<S>> legalTransitions) {
        this.current = new AtomicReference<>(initial);
        this.legalTransitions = legalTransitions;
    }

    /** CAS 跃迁 */
    public TransitionResult transition(S expected, S target) {
        Set<S> allowed = legalTransitions.getOrDefault(expected, Collections.emptySet());
        if (!allowed.contains(target)) {
            throw new IllegalStateTransitionException(expected, target);
        }
        boolean success = current.compareAndSet(expected, target);
        return success ? TransitionResult.SUCCESS : TransitionResult.CONFLICT;
    }

    public TransitionResult transition(S target) {
        return transition(current.get(), target);
    }

    public S current() {
        return current.get();
    }
}
