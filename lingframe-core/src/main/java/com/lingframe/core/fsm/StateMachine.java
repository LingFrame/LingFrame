package com.lingframe.core.fsm;

import com.lingframe.core.exception.IllegalStateTransitionException;
import com.lingframe.core.event.EventBus;
import com.lingframe.api.event.LingStateChangedEvent;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用状态机执行器
 * 使用分发锁或 CAS 保证状态流转的安全性和一致性。
 * 
 * @param <S> 状态枚举类型
 */
public class StateMachine<S extends Enum<S>> {
    private final AtomicReference<S> current;
    private final Map<S, Set<S>> legalTransitions;
    private final String contextId;
    private final EventBus eventBus;

    public StateMachine(String contextId, S initial, Map<S, Set<S>> legalTransitions, EventBus eventBus) {
        this.contextId = contextId;
        this.current = new AtomicReference<>(initial);
        this.legalTransitions = legalTransitions;
        this.eventBus = eventBus;
    }

    /**
     * 执行带期望值的 CAS 状态跃迁
     * 
     * @param expected 当前期望状态
     * @param target   目标状态
     * @return 转换结果（成功或冲突）
     */
    public TransitionResult transition(S expected, S target) {
        Set<S> allowed = legalTransitions.getOrDefault(expected, Collections.emptySet());
        if (!allowed.contains(target)) {
            throw new IllegalStateTransitionException(expected, target);
        }
        boolean success = current.compareAndSet(expected, target);
        if (success && eventBus != null) {
            eventBus.publish(new LingStateChangedEvent(
                    contextId, expected.name(), target.name()));
        }
        return success ? TransitionResult.SUCCESS : TransitionResult.CONFLICT;
    }

    public TransitionResult transition(S target) {
        return transition(current.get(), target);
    }

    public S current() {
        return current.get();
    }
}
