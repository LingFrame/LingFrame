package com.lingframe.core.spi;

import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginSlot;

import java.lang.reflect.Method;

/**
 * 治理策略提供者 (责任链模式) SPI
 */
public interface GovernancePolicyProvider {

    /**
     * 排序优先级 (越小越优先)
     */
    int getOrder();

    /**
     * 解析治理决策
     * @param slot 当前插件槽位
     * @param method 目标方法
     * @param ctx 调用上下文
     * @return 决策结果，如果无法决策返回 null (责任链继续)
     */
    GovernanceDecision resolve(PluginSlot slot, Method method, InvocationContext ctx);
}
