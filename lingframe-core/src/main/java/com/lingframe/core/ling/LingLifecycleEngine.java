package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import java.io.File;
import java.util.Map;

/**
 * LingLifecycleEngine 是组件装载和生命流的唯一驱动者。
 * 它持有诸如 FSM (StateMachine) 的控制权把柄，将外部的部署指令转译为 FSM 动作并驱动其跃迁。
 * 接管加载与卸载的核心装配流水线。
 */
public interface LingLifecycleEngine {

    ClassLoader getClassLoader(String lingId);

    /**
     * 根据提供的物理文件或虚拟路径进行完整的凌组件装载过程。
     * 包括读取清单、校验、放入 Repository、通知 ServiceRegistry，直到推进至就绪态。
     */
    void deploy(LingDefinition lingDefinition, File sourceFile, boolean isDefault,
            Map<String, String> labels);

    /**
     * 热重载专用：允许同版本并存，先新建再切换流量
     */
    default void deployForReload(LingDefinition lingDefinition, File sourceFile, boolean isDefault,
            Map<String, String> labels) {
        deploy(lingDefinition, sourceFile, isDefault, labels);
    }

    /**
     * 推演生命周期流以停用组件并回收相关强引用。
     * 结束后会触发 InstanceDestroyedEvent 并经由 ResourceManager 清空一切物理痕迹。
     */
    void undeploy(String lingId);

    /**
     * 根据特定版本卸载灵元。
     * 如果这是该灵元的最后一个活跃版本，则会触发全量资源的同步清扫。
     * 
     * @param lingId  灵元ID
     * @param version 目标卸载版本
     */
    void undeploy(String lingId, String version);

    /**
     * 按实例卸载（用于热重载旧实例清理）
     */
    default void undeploy(String lingId, LingInstance instance) {
        if (instance != null) {
            undeploy(lingId, instance.getVersion());
        }
    }
}
