package com.lingframe.core.ling;

import com.lingframe.core.spi.RoutableTarget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灵元运行时仓储默认实现
 * 负责在内存中维护所有已加载灵元的运行时对象 (LingRuntime)。
 * <p>
 * 路由升维：runtimes map 类型从 {@code Map<String, LingRuntime>} 升级为
 * {@code Map<String, RoutableTarget>}，使灵核 ({@link LingCoreRoutableTarget})
 * 和灵元 ({@link LingRuntime}) 共用同一 map。
 * <p>
 * 单 map 升级原则：无双写，无一致性问题。
 * LingRuntime 实现 RoutableTarget 后天然能进这个 map，是同一份数据两种视图。
 * 旧方法 {@link #getRuntime} 内部 {@code instanceof} 转型，灵核返回 null 保持旧语义。
 */
public class DefaultLingRepository implements LingRepository {
    /** 灵元/灵核 ID 与路由目标的映射表（单 map，无双写） */
    private final Map<String, RoutableTarget> runtimes = new ConcurrentHashMap<>();

    @Override
    public void register(LingRuntime runtime) {
        if (runtime == null || runtime.getLingId() == null) {
            throw new IllegalArgumentException("Runtime or LingId cannot be null");
        }
        // LingRuntime 实现 RoutableTarget，直接写入升级后的 map
        runtimes.put(runtime.getLingId(), runtime);
    }

    @Override
    public LingRuntime deregister(String lingId) {
        RoutableTarget removed = runtimes.remove(lingId);
        // 旧方法语义：只返回 LingRuntime，灵核返回 null
        return removed instanceof LingRuntime ? (LingRuntime) removed : null;
    }

    @Override
    public LingRuntime getRuntime(String lingId) {
        RoutableTarget target = runtimes.get(lingId);
        // 旧方法语义：只返回 LingRuntime，灵核（LingCoreRoutableTarget）返回 null
        // 这是预期行为——dashboard 等老调用方查灵核时拿到 null，不会误操作灵核状态
        return target instanceof LingRuntime ? (LingRuntime) target : null;
    }

    @Override
    public boolean hasRuntime(String lingId) {
        return runtimes.containsKey(lingId);
    }

    @Override
    public Collection<LingRuntime> getAllRuntimes() {
        // 旧方法语义：只返回 LingRuntime 集合，过滤掉灵核
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
    public RoutableTarget deregisterRoutableTarget(String lingId) {
        return runtimes.remove(lingId);
    }
}
