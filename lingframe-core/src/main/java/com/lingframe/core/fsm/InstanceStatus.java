package com.lingframe.core.fsm;

import java.util.*;
import com.lingframe.core.event.EventBus;

public enum InstanceStatus {
    CREATED, // 刚构造，未开始加载
    LOADING, // 加载字节码 + 权限/链路/审计校验（合并为内部步骤）
    STARTING, // 拉起 Spring Context / 依赖注入 / 线程池
    READY, // 可以接受流量
    INACTIVE, // 停用（保留实例但不参与路由）
    STOPPING, // 优雅关闭中
    DEAD, // 资源已释放，等待 GC
    ERROR; // 异常态

    public static final Map<InstanceStatus, Set<InstanceStatus>> TRANSITIONS;

    static {
        Map<InstanceStatus, Set<InstanceStatus>> map = new EnumMap<>(InstanceStatus.class);
        map.put(CREATED, EnumSet.of(LOADING, ERROR));
        map.put(LOADING, EnumSet.of(STARTING, ERROR));
        map.put(STARTING, EnumSet.of(READY, ERROR));
        map.put(READY, EnumSet.of(INACTIVE, STOPPING, ERROR));
        map.put(INACTIVE, EnumSet.of(READY, STOPPING, ERROR));
        map.put(STOPPING, EnumSet.of(DEAD));
        map.put(ERROR, EnumSet.of(STOPPING, DEAD));
        map.put(DEAD, Collections.emptySet());
        TRANSITIONS = Collections.unmodifiableMap(map);
    }

    public static StateMachine<InstanceStatus> newMachine(String lingId, EventBus eventBus) {
        return new StateMachine<>(lingId, CREATED, TRANSITIONS, eventBus);
    }
}
