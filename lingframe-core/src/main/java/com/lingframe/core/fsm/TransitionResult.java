package com.lingframe.core.fsm;

public enum TransitionResult {
    SUCCESS, // 跃迁成功
    CONFLICT // 并发竞争，当前状态已变
}
