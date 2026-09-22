package com.lingframe.core.spi;

import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.ling.LingInstance;

import java.util.List;

/**
 * SPI: 流量路由策略
 * <p>
 * 职责：从众多实例中选出一个最佳实例
 * <p>
 * 设计说明：
 * - 此接口专注于路由决策（从候选实例中选一）
 * - 金丝雀/迭代灰度统一走权重路由（ProviderWeightRouter 按权重分流），不再有独立的金丝雀配置接口
 */
public interface TrafficRouter {

    /**
     * 路由决策：从候选实例中选择最佳目标
     *
     * @param candidates 候选实例列表
     * @param context    调用上下文
     * @return 选中的目标实例
     */
    LingInstance route(List<LingInstance> candidates, InvocationContext context);
}