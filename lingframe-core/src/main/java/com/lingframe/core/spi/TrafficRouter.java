package com.lingframe.core.spi;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;

import java.util.List;

/**
 * SPI: 流量路由策略
 * 职责：从众多实例中选出一个最佳实例
 */
public interface TrafficRouter {
    PluginInstance route(List<PluginInstance> candidates, InvocationContext context);
}