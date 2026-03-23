package com.lingframe.core.fsm;

/**
 * 状态跃迁结果。
 * <p>
 * 封装结果码与跃迁时的状态快照（from / target），
 * 保证 from 值与 CAS 操作瞬间的快照严格一致，杜绝"幽灵事件"。
 *
 * @param <S> 状态枚举类型
 */
public final class TransitionResult<S extends Enum<S>> {

    /**
     * 跃迁结果码
     */
    public enum Code {
        /**
         * 跃迁成功
         */
        SUCCESS,
        /**
         * CAS 竞争失败，当前状态已被其他线程修改
         */
        CONFLICT,
        /**
         * 非法跃迁，转换表中不存在此路径
         */
        ILLEGAL
    }

    private final Code code;
    private final S from;
    private final S target;

    private TransitionResult(Code code, S from, S target) {
        this.code = code;
        this.from = from;
        this.target = target;
    }

    /* ---------- 静态工厂（包级可见，仅 StateMachine 调用） ---------- */

    static <S extends Enum<S>> TransitionResult<S> success(S from, S target) {
        return new TransitionResult<>(Code.SUCCESS, from, target);
    }

    static <S extends Enum<S>> TransitionResult<S> conflict(S from, S target) {
        return new TransitionResult<>(Code.CONFLICT, from, target);
    }

    static <S extends Enum<S>> TransitionResult<S> illegal(S from, S target) {
        return new TransitionResult<>(Code.ILLEGAL, from, target);
    }

    /* ---------- 查询 ---------- */

    public boolean isSuccess() {
        return code == Code.SUCCESS;
    }

    public boolean isConflict() {
        return code == Code.CONFLICT;
    }

    public boolean isIllegal() {
        return code == Code.ILLEGAL;
    }

    public Code code() {
        return code;
    }

    /**
     * CAS 操作时的状态快照
     */
    public S from() {
        return from;
    }

    /**
     * 本次跃迁的目标状态
     */
    public S target() {
        return target;
    }

    @Override
    public String toString() {
        return String.format("Transition{%s: %s → %s}", code, from, target);
    }
}