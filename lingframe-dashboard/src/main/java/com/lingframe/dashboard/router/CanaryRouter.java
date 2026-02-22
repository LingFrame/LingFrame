package com.lingframe.dashboard.router;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.CanaryConfigurable;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.api.exception.InvalidArgumentException;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 金丝雀灰度路由实现
 * <p>
 * 同时实现 TrafficRouter（路由决策）和 CanaryConfigurable（配置管理）
 */
@Slf4j
public class CanaryRouter implements TrafficRouter, CanaryConfigurable {

    private final TrafficRouter delegate; // 原有的 LabelMatchRouter

    // 灰度配置：lingId -> percent (0-100)
    private final Map<String, CanaryConfig> canaryConfigs = new ConcurrentHashMap<>();

    public CanaryRouter(TrafficRouter delegate) {
        this.delegate = delegate;
    }

    @Override
    public LingInstance route(List<LingInstance> candidates, InvocationContext context) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String lingId = context != null ? context.getLingId() : null;
        CanaryConfig config = lingId != null ? canaryConfigs.get(lingId) : null;

        // 有灰度配置，按比例路由
        if (config != null && config.percent > 0) {
            boolean routeToCanary = ThreadLocalRandom.current().nextInt(100) < config.percent;

            if (routeToCanary) {
                // 找灰度实例（非默认的，或匹配特定版本）
                LingInstance canaryInstance = findCanaryInstance(candidates, config.canaryVersion);
                if (canaryInstance != null) {
                    return canaryInstance;
                }
            }
            // 否则返回默认（稳定版）
            return findStableInstance(candidates);
        }

        // 无灰度配置，委托给原路由器
        return delegate.route(candidates, context);
    }

    /**
     * 设置灰度配置
     */
    public void setCanaryConfig(String lingId, int percent, String canaryVersion) {
        if (percent < 0 || percent > 100) {
            throw new InvalidArgumentException("percent", "Canary percent must be 0-100");
        }

        if (percent == 0) {
            canaryConfigs.remove(lingId);
            log.info("[{}] Canary disabled", lingId);
        } else {
            canaryConfigs.put(lingId, new CanaryConfig(percent, canaryVersion));
            log.info("[{}] Canary config: {}% -> {}", lingId, percent, canaryVersion);
        }
    }

    /**
     * 获取灰度配置
     */
    public CanaryConfig getCanaryConfig(String lingId) {
        return canaryConfigs.get(lingId);
    }

    /**
     * 获取灰度比例
     */
    @Override
    public int getCanaryPercent(String lingId) {
        CanaryConfig config = canaryConfigs.get(lingId);
        return config != null ? config.percent : 0;
    }

    private LingInstance findCanaryInstance(List<LingInstance> candidates, String canaryVersion) {
        // 优先匹配指定版本
        if (canaryVersion != null) {
            for (LingInstance inst : candidates) {
                if (canaryVersion.equals(inst.getDefinition().getVersion())) {
                    return inst;
                }
            }
        }

        // 否则返回第二个实例（假设第一个是稳定版）
        return candidates.size() > 1 ? candidates.get(1) : null;
    }

    private LingInstance findStableInstance(List<LingInstance> candidates) {
        // 第一个通常是默认/稳定版
        return candidates.isEmpty() ? null : candidates.get(0);
    }


    @Value
    public static class CanaryConfig {
        int percent;
        String canaryVersion;
    }
}