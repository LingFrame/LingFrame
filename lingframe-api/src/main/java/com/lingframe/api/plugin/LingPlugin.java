package com.lingframe.api.plugin;

import com.lingframe.api.context.PluginContext;

/**
 * 插件生命周期接口
 * 所有插件的主入口类必须实现此接口
 * 
 * @author LingFrame
 */
public interface LingPlugin {

    /**
     * 插件启动时调用
     * 
     * @param context 插件上下文，提供环境交互能力
     */
    default void onStart(PluginContext context) {
        // Default empty implementation
    }

    /**
     * 插件停止时调用
     * 用于释放资源
     * 
     * @param context 插件上下文
     */
    default void onStop(PluginContext context) {
        // Default empty implementation
    }
}
