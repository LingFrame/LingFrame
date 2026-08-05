package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 静态磁盘包模型，承载扫描到的 JAR 文件元数据及声明权限契约。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LingPackageDTO {
    /** 灵元ID */
    private String lingId;
    /** 灵元版本 */
    private String version;
    /** 物理包文件名 */
    private String fileName;
    /** 包文件大小（字节） */
    private long fileSize;
    /** 灵核入口类全限定名 */
    private String mainClass;
    /** 是否已被部署装载 */
    private boolean isInstalled;
    /** 声明的安全与资源权限要求 */
    private List<String> permissions;
}
