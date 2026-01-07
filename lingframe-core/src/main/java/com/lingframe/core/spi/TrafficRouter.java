package com.lingframe.core.spi;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.router.CanaryRouter;

import java.util.List;

/**
 * SPI: 流量路由策略
 * 职责：从众多实例中选出一个最佳实例
 */
public interface TrafficRouter {
    PluginInstance route(List<PluginInstance> candidates, InvocationContext context);

    // 供 CanaryRouter 实现
    default void setCanaryConfig(String pluginId, int percent, String canaryVersion) {
        // 默认空实现，CanaryRouter 会覆盖
    }

    default CanaryRouter.CanaryConfig getCanaryConfig(String pluginId) {
        return null;
    }

    default int getCanaryPercent(String pluginId) {
        return 0;
    }
}