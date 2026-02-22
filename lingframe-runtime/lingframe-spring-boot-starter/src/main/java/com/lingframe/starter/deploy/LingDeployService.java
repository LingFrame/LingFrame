package com.lingframe.starter.deploy;

import java.io.File;

/**
 * 宿主级别的高级部署门面。
 * <p>
 * 提供更高维度的统一下载与安装能力，隔离底层纯净的微内核 (Core)。
 * 未来可以扩展支持 http:// 或 oss:// 等基于 URI 的资源拉取。
 */
public interface LingDeployService {

    /**
     * 通过资源 URI 部署模块
     *
     * @param uri       资源统一标识符（如 file://, http://）
     * @param isDefault 是否作为默认版本
     */
    void deploy(String uri, boolean isDefault) throws Exception;

    /**
     * 通过本地文件直属部署
     */
    void deploy(File file, boolean isDefault) throws Exception;
}
