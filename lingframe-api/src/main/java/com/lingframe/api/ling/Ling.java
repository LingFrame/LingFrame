package com.lingframe.api.ling;

import com.lingframe.api.context.LingContext;

/**
 * 单元生命周期接口
 * <p>
 * 所有单元的主入口类必须实现此接口（Springboot单元可以不实现）
 * 
 * @author LingFrame
 */
public interface Ling {

    /**
     * 单元启动时调用
     * 
     * @param context 单元上下文，提供环境交互能力
     */
    default void onStart(LingContext context) {
        // Default empty implementation
    }

    /**
     * 单元停止时调用
     * 用于释放资源
     * 
     * @param context 单元上下文
     */
    default void onStop(LingContext context) {
        // Default empty implementation
    }
}
