package com.lingframe.core.spi;

import java.io.File;

/**
 * 单元类加载器工厂 SPI
 */
public interface LingLoaderFactory {
    ClassLoader create(String lingId, File sourceFile, ClassLoader parent);
}