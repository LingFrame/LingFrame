package com.lingframe.api.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 灵元运行时状态变更事件
 * 当灵元状态在 StateMachine 中发生跃迁（如 ACTIVE -> INACTIVE）时触发
 */
@Getter
@RequiredArgsConstructor
public class LingStateChangedEvent implements LingEvent {

    /**
     * 发生状态变更的灵元ID
     */
    private final String lingId;

    /**
     * 变更前的状态 (Enum.name())
     */
    private final String previousState;

    /**
     * 变更后的状态 (Enum.name())
     */
    private final String currentState;
}
