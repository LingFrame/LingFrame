package com.lingframe.core.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 全局配置中心
 */
public class LingFrameConfig {

    /**
     * 是否开启开发模式
     */
    @Setter
    @Getter
    private static volatile boolean devMode = false;

}