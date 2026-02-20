package com.lingframe.core.spi;

import java.io.File;

/**
 * 单元安全验证器 SPI
 * 专门用于在安装前执行签名校验、哈希比对等阻断性操作
 */
public interface LingSecurityVerifier {

    /**
     * 校验单元包
     *
     * @throws SecurityException 如果校验失败，抛出异常阻止安装
     */
    void verify(String lingId, File sourceFile) throws SecurityException;
}