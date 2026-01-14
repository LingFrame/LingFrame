package com.lingframe.core.spi;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;

import java.util.List;

/**
 * SPI: 流量路由策略
 * <p>
 * 职责：从众多实例中选出一个最佳实例
 * <p>
 * 设计说明：
 * - 此接口专注于路由决策
 * - 金丝雀配置管理请使用 {@link CanaryConfigurable} 接口
 * - 如需同时支持路由和金丝雀配置，实现类可同时实现两个接口
 */
public interface TrafficRouter {

    /**
     * 路由决策：从候选实例中选择最佳目标
     *
     * @param candidates 候选实例列表
     * @param context    调用上下文
     * @return 选中的目标实例
     */
    PluginInstance route(List<PluginInstance> candidates, InvocationContext context);
}