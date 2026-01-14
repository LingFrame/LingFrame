package com.lingframe.core.spi;

/**
 * 金丝雀配置接口 (从 TrafficRouter 拆分)
 * <p>
 * 职责：管理金丝雀/灰度发布的配置
 * <p>
 * 设计说明：将配置管理与路由决策分离，符合单一职责原则
 */
public interface CanaryConfigurable {

    /**
     * 设置金丝雀配置
     *
     * @param pluginId      插件ID
     * @param percent       金丝雀流量百分比 (0-100)
     * @param canaryVersion 金丝雀版本号
     */
    void setCanaryConfig(String pluginId, int percent, String canaryVersion);

    /**
     * 获取金丝雀流量百分比
     *
     * @param pluginId 插件ID
     * @return 百分比 (0 表示无金丝雀)
     */
    int getCanaryPercent(String pluginId);

    /**
     * 获取金丝雀版本号
     *
     * @param pluginId 插件ID
     * @return 版本号，如果没有则返回 null
     */
    default String getCanaryVersion(String pluginId) {
        return null;
    }

    /**
     * 清除金丝雀配置
     *
     * @param pluginId 插件ID
     */
    default void clearCanaryConfig(String pluginId) {
        setCanaryConfig(pluginId, 0, null);
    }
}
