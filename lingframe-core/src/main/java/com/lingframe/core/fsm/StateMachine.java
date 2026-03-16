package com.lingframe.core.fsm;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用有限状态机。
 * <p>
 * 基于声明式转换表 + {@link AtomicReference#compareAndSet CAS} 实现无锁线程安全的状态流转。
 * <b>职责单一</b>：只做状态管理，不包含事件发布、日志等领域逻辑，保持纯粹可测试。
 *
 * @param <S> 状态枚举类型
 */
public class StateMachine<S extends Enum<S>> {

    /**
     * 当前状态（原子引用，支持无锁 CAS）
     */
    private final AtomicReference<S> current;

    /**
     * 合法跃迁表：from → Set<to>（不可变）
     */
    private final Map<S, Set<S>> legalTransitions;

    /**
     * 上下文标识（lingId 等），用于日志与调试
     */
    private final String contextId;

    /**
     * @param contextId        上下文标识
     * @param initial          初始状态
     * @param legalTransitions 合法转换表（应为不可变 Map）
     */
    public StateMachine(String contextId, S initial, Map<S, Set<S>> legalTransitions) {
        this.contextId = contextId;
        this.current = new AtomicReference<>(initial);
        this.legalTransitions = legalTransitions;
    }

    /**
     * 以当前快照为期望值执行 CAS 跃迁。
     * <p>
     * 内部先读快照，校验合法性后通过 CAS 原子写入。
     * 返回的 {@link TransitionResult#from()} 与 CAS 时的快照严格一致。
     *
     * @param target 目标状态
     * @return 跃迁结果（SUCCESS / CONFLICT / ILLEGAL）
     */
    public TransitionResult<S> transition(S target) {
        S snapshot = current.get();

        // 幂等：目标与当前相同，视为成功（不触发副作用）
        if (snapshot == target) {
            return TransitionResult.success(snapshot, target);
        }

        // 校验转换合法性
        Set<S> allowed = legalTransitions.getOrDefault(snapshot, Collections.emptySet());
        if (!allowed.contains(target)) {
            return TransitionResult.illegal(snapshot, target);
        }

        // CAS 写入，失败说明另一线程已抢先修改
        boolean ok = current.compareAndSet(snapshot, target);
        return ok
                ? TransitionResult.success(snapshot, target)
                : TransitionResult.conflict(snapshot, target);
    }

    /**
     * 以显式期望值执行 CAS 跃迁（调用方已持有快照时使用）
     *
     * @param expected 期望的当前状态
     * @param target   目标状态
     * @return 跃迁结果
     */
    public TransitionResult<S> transition(S expected, S target) {
        if (expected == target) {
            return TransitionResult.success(expected, target);
        }

        Set<S> allowed = legalTransitions.getOrDefault(expected, Collections.emptySet());
        if (!allowed.contains(target)) {
            return TransitionResult.illegal(expected, target);
        }

        boolean ok = current.compareAndSet(expected, target);
        return ok
                ? TransitionResult.success(expected, target)
                : TransitionResult.conflict(expected, target);
    }

    /**
     * 获取当前状态快照
     */
    public S current() {
        return current.get();
    }

    /**
     * 上下文标识
     */
    public String contextId() {
        return contextId;
    }

    @Override
    public String toString() {
        return String.format("StateMachine{id='%s', state=%s}", contextId, current.get());
    }
}
