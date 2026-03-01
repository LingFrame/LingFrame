package com.lingframe.core.ling;

import java.io.File;

/**
 * LingLifecycleEngine 是组件装载和生命流的唯一驱动者。
 * 它持有诸如 FSM (StateMachine) 的控制权把柄，将外部的部署指令转译为 FSM 动作并驱动其跃迁。
 * 在 M3 彻底推翻 LingManager 时，它接管加载与卸载的核心装配流水线。
 */
public interface LingLifecycleEngine {

    /**
     * 根据提供的物理文件或虚拟路径进行完整的凌组件装载过程。
     * 包括读取清单、校验、放入 Repository、通知 ServiceRegistry，直到推进至就绪态。
     */
    void deploy(com.lingframe.api.config.LingDefinition lingDefinition, File sourceFile, boolean isDefault,
            java.util.Map<String, String> labels);

    /**
     * 推演生命周期流以停用组件并回收相关强引用。
     * 结束后会触发 InstanceDestroyedEvent 并经由 ResourceManager 清空一切物理痕迹。
     */
    void undeploy(String lingId);
}
