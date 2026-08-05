package com.lingframe.core.spi;

import com.lingframe.api.config.LingDefinition;

import java.io.File;

/**
 * 热重载观察器内核抽象。
 * <p>
 * 微内核解耦：内核（ling 包）只依赖此接口，具体实现由 dev 扩展包提供。
 */
public interface LingHotSwapWatcher {

    /**
     * 注册灵元源目录以监听变更。
     */
    void register(String lingId, File classesDir, LingDefinition definition);

    /**
     * 取消灵元源目录的变更监听。
     */
    void unregister(String lingId);
}
