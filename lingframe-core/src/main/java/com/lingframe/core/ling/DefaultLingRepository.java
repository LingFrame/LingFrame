package com.lingframe.core.ling;

import com.lingframe.core.spi.RoutableTarget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灵元运行时仓储默认实现。
 * 负责在内存中维护所有已加载灵元的运行时对象 (LingRuntime)。
 * <p>
 * 路由升维：runtimes map 类型为 {@code Map<String, RoutableTarget>}，
 * 使灵核 ({@link LingCoreRoutableTarget}) 和灵元 ({@link LingRuntime}) 共用同一 map。
 * 单 map：无双写。
 * {@link #getRuntime} 仅返回 {@link LingRuntime}；灵核请走 {@link #getRoutableTarget}。
 */
public class DefaultLingRepository implements LingRepository {
    /** 灵元/灵核 ID 与路由目标的映射表（单 map，无双写） */
    private final Map<String, RoutableTarget> runtimes = new ConcurrentHashMap<>();

    @Override
    public void register(LingRuntime runtime) {
        if (runtime == null || runtime.getLingId() == null) {
            throw new IllegalArgumentException("Runtime or LingId cannot be null");
        }
        // LingRuntime 实现 RoutableTarget，直接写入 map
        runtimes.put(runtime.getLingId(), runtime);
    }

    @Override
    public LingRuntime unregister(String lingId) {
        RoutableTarget removed = runtimes.remove(lingId);
        // 只返回 LingRuntime；若移除的是灵核路由目标则返回 null
        return removed instanceof LingRuntime ? (LingRuntime) removed : null;
    }

    @Override
    public LingRuntime getRuntime(String lingId) {
        RoutableTarget target = runtimes.get(lingId);
        // 只返回 LingRuntime；灵核（LingCoreRoutableTarget）返回 null，
        // 避免 dashboard 等调用方误把灵核当灵元操作
        return target instanceof LingRuntime ? (LingRuntime) target : null;
    }

    @Override
    public boolean hasRuntime(String lingId) {
        return runtimes.containsKey(lingId);
    }

    @Override
    public Collection<LingRuntime> getAllRuntimes() {
        // 只返回 LingRuntime 集合，过滤掉灵核
        List<LingRuntime> result = new ArrayList<>();
        for (RoutableTarget target : runtimes.values()) {
            if (target instanceof LingRuntime) {
                result.add((LingRuntime) target);
            }
        }
        return result;
    }

    @Override
    public RoutableTarget getRoutableTarget(String lingId) {
        return runtimes.get(lingId);
    }

    @Override
    public void registerRoutableTarget(RoutableTarget target) {
        if (target == null || target.getLingId() == null) {
            throw new IllegalArgumentException("RoutableTarget or LingId cannot be null");
        }
        runtimes.put(target.getLingId(), target);
    }

    @Override
    public RoutableTarget unregisterRoutableTarget(String lingId) {
        return runtimes.remove(lingId);
    }
}
