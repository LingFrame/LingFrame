package com.lingframe.core.fsm;

import java.util.*;

/**
 * 实例级生命周期状态。
 * <p>
 * 描述单个 {@code LingInstance} 从创建到销毁的完整生命周期。
 * 设计为<b>单向管道</b>（无环），保证状态机的可预测性：
 * <pre>
 * CREATED → LOADING → STARTING → READY → STOPPING → DEAD
 *    ↘         ↘          ↘        ↘         ↘
 *                       ERROR ───→ STOPPING / DEAD
 * </pre>
 * "是否参与路由"由流量策略层决定，不体现为实例状态。
 * RuntimeStatus 由多个 InstanceStatus 聚合而来，但两层状态机彼此不直接写对方。
 */
public enum InstanceStatus {

    /**
     * 刚构造，尚未开始加载
     */
    CREATED,
    /**
     * 加载字节码、权限校验、链路审计等
     */
    LOADING,
    /**
     * 拉起 Spring Context / 依赖注入 / 线程池初始化
     */
    STARTING,
    /**
     * 可接受流量
     */
    READY,
    /**
     * 优雅关闭中，排空存量请求
     */
    STOPPING,
    /**
     * 资源已释放，等待 GC（终态）
     */
    DEAD,
    /**
     * 异常态，可由任何活跃状态进入
     */
    ERROR;

    /**
     * 合法转换表（不可变）
     */
    public static final Map<InstanceStatus, Set<InstanceStatus>> TRANSITIONS;

    static {
        Map<InstanceStatus, Set<InstanceStatus>> m = new EnumMap<>(InstanceStatus.class);
        m.put(CREATED, EnumSet.of(LOADING, ERROR));
        m.put(LOADING, EnumSet.of(STARTING, ERROR));
        m.put(STARTING, EnumSet.of(READY, ERROR));
        m.put(READY, EnumSet.of(STOPPING, ERROR));
        m.put(STOPPING, EnumSet.of(DEAD, ERROR));  // 关闭失败允许进入 ERROR
        m.put(ERROR, EnumSet.of(STOPPING, DEAD));   // 可重试关闭或直接销毁
        m.put(DEAD, Collections.emptySet());       // 终态，不可跃迁
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    /**
     * 创建以 {@link #CREATED} 为初始状态的实例级状态机
     *
     * @param lingId 实例标识
     */
    public static StateMachine<InstanceStatus> newMachine(String lingId) {
        return new StateMachine<>(lingId, CREATED, TRANSITIONS);
    }
}
