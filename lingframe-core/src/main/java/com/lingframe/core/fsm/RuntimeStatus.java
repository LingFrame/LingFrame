package com.lingframe.core.fsm;

import java.util.*;
import com.lingframe.core.event.EventBus;

public enum RuntimeStatus {
    INACTIVE, // 已注册但无可用实例
    ACTIVE, // 正常服役（灰度期间也保持 ACTIVE）
    DEGRADED, // 降级（健康检查失败/熔断触发，可自愈回 ACTIVE）
    STOPPING, // 优雅关闭中，排空存量请求
    REMOVED; // 已移除，不可恢复

    public static final Map<RuntimeStatus, Set<RuntimeStatus>> TRANSITIONS;

    static {
        Map<RuntimeStatus, Set<RuntimeStatus>> map = new EnumMap<>(RuntimeStatus.class);
        map.put(INACTIVE, EnumSet.of(ACTIVE, REMOVED));
        map.put(ACTIVE, EnumSet.of(DEGRADED, STOPPING));
        map.put(DEGRADED, EnumSet.of(ACTIVE, STOPPING));
        map.put(STOPPING, EnumSet.of(REMOVED));
        map.put(REMOVED, Collections.emptySet());
        TRANSITIONS = Collections.unmodifiableMap(map);
    }

    public static StateMachine<RuntimeStatus> newMachine(String lingId, EventBus eventBus) {
        return new StateMachine<>(lingId, INACTIVE, TRANSITIONS, eventBus);
    }
}
