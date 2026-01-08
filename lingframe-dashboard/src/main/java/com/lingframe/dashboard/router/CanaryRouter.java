package com.lingframe.dashboard.router;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.spi.TrafficRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 金丝雀灰度路由实现
 */
@Slf4j
public class CanaryRouter implements TrafficRouter {

    private final TrafficRouter delegate;  // 原有的 LabelMatchRouter

    // 灰度配置：pluginId -> percent (0-100)
    private final Map<String, CanaryConfig> canaryConfigs = new ConcurrentHashMap<>();

    public CanaryRouter(TrafficRouter delegate) {
        this.delegate = delegate;
    }

    @Override
    public PluginInstance route(List<PluginInstance> candidates, InvocationContext context) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String pluginId = context != null ? context.getPluginId() : null;
        CanaryConfig config = pluginId != null ? canaryConfigs.get(pluginId) : null;

        // 有灰度配置，按比例路由
        if (config != null && config.percent > 0) {
            boolean routeToCanary = ThreadLocalRandom.current().nextInt(100) < config.percent;

            if (routeToCanary) {
                // 找灰度实例（非默认的，或匹配特定版本）
                PluginInstance canaryInstance = findCanaryInstance(candidates, config.canaryVersion);
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
    public void setCanaryConfig(String pluginId, int percent, String canaryVersion) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Canary percent must be 0-100");
        }

        if (percent == 0) {
            canaryConfigs.remove(pluginId);
            log.info("[{}] Canary disabled", pluginId);
        } else {
            canaryConfigs.put(pluginId, new CanaryConfig(percent, canaryVersion));
            log.info("[{}] Canary config: {}% -> {}", pluginId, percent, canaryVersion);
        }
    }

    /**
     * 获取灰度配置
     */
    public CanaryConfig getCanaryConfig(String pluginId) {
        return canaryConfigs.get(pluginId);
    }

    /**
     * 获取灰度比例
     */
    @Override
    public int getCanaryPercent(String pluginId) {
        CanaryConfig config = canaryConfigs.get(pluginId);
        return config != null ? config.percent : 0;
    }

    private PluginInstance findCanaryInstance(List<PluginInstance> candidates, String canaryVersion) {
        // 优先匹配指定版本
        if (canaryVersion != null) {
            for (PluginInstance inst : candidates) {
                if (canaryVersion.equals(inst.getDefinition().getVersion())) {
                    return inst;
                }
            }
        }

        // 否则返回第二个实例（假设第一个是稳定版）
        return candidates.size() > 1 ? candidates.get(1) : null;
    }

    private PluginInstance findStableInstance(List<PluginInstance> candidates) {
        // 第一个通常是默认/稳定版
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public record CanaryConfig(int percent, String canaryVersion) {
    }
}