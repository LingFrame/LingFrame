package com.lingframe.core.runtime;

/**
 * 运行时模式（dev/prod）抽象。
 * <p>
 * 将"运行时模式"从 {@link LingFrameConfig} 中剥离为独立概念：
 * <ul>
 *   <li>{@link FixedRuntimeMode}：不可变实现，从配置基线读取，不支持运行时切换</li>
 *   <li>{@link SwitchableRuntimeMode}：可切换实现，密码认证 + 失败锁定</li>
 * </ul>
 * 设计目标：在保持 {@link LingFrameConfig} 不可变的前提下，
 * 通过持有不可变的 {@link RuntimeMode} 引用（内部 volatile 可变）实现运行时可切换。
 * <p>
 * 线程安全：实现类需保证 {@link #isDev()} 的读取线程安全。
 */
public interface RuntimeMode {

    /**
     * 当前是否为开发模式。
     * <p>
     * 消费方每次调用都会读取最新状态，支持运行时切换。
     *
     * @return true 表示开发模式
     */
    boolean isDev();

    /**
     * 运行时切换是否启用。
     * <p>
     * fail-closed 语义：未配置密码时返回 false，拒绝一切运行时切换请求。
     *
     * @return true 表示已配置密码，允许运行时切换
     */
    boolean isSwitchEnabled();
}
