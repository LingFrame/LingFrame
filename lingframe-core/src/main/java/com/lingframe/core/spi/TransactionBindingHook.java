package com.lingframe.core.spi;

import java.sql.Connection;
import java.util.Set;

/**
 * 事务状态提取 SPI：供 Pipeline 过滤器在 TCCL 切换前判断当前线程是否存在活跃事务并提取绑定连接。
 * <p>
 * 实现方（runtime starter 的 Spring 实现）负责对接具体生态（{@code TransactionSynchronizationManager}）。
 * core 只依赖该 SPI，不引入任何 Spring 依赖——保持 core 零 Spring 的模块边界。
 * <p>
 * 带数据源身份维度：活跃事务按受管代理实例（TSM 资源键）绑定，本 SPI 按 dataSourceId
 * 提取对应连接；身份门控由消费侧（受管数据源代理按自身 id 精确查栈）完成，混合链路下
 * 私有库灵元永不误用受管连接。
 */
public interface TransactionBindingHook {

    /** 当前线程是否存在活跃事务 */
    boolean isTransactionActive();

    /**
     * 当前活跃事务实际绑定的受管数据源身份集合（模式 1 恒为 {"default"}）。
     * Filter 按该集合逐源压栈；无受管绑定时返回空集（如 JPA 根，物理连接封装在
     * EntityManager 内不可提取，穿透不激活）。
     *
     * @return 活跃绑定源集合（可为空集，不为 null）
     */
    Set<String> getActiveBoundDataSourceIds();

    /**
     * 提取绑定到指定受管数据源（TSM 资源键 = 受管代理实例）的物理连接视图；
     * 该源无绑定时返回 null。
     *
     * @param dataSourceId 受管数据源身份
     * @return 连接视图（治理代理）；无绑定时返回 null
     */
    Connection getBoundConnection(String dataSourceId);
}
