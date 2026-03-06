package com.lingframe.core.spi;

import java.io.File;

/**
 * 容器工厂 SPI
 */
public interface ContainerFactory {

    /**
     * 创建容器实例
     * 
     * @param lingId      灵元ID
     * @param jarFile     灵元 Jar 文件
     * @param classLoader 灵元专用的类加载器
     * @return 容器实例
     */
    LingContainer create(String lingId, File jarFile, ClassLoader classLoader);
}