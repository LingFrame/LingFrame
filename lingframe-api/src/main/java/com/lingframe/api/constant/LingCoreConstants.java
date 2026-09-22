package com.lingframe.api.constant;

/**
 * 灵核进程级常驻标识常量。
 * <p>
 * 灵核 lingId 固定为 {@value #LINGCORE_LING_ID}，version 固定为 {@value #LINGCORE_VERSION}，
 * 不支持热加载/热卸载，随进程生死。
 * <p>
 * 灵核与灵元在路由层平级共存于 provider 索引：
 * 灵核 Bean 由 {@code LingCoreServiceRegistrarProcessor} 注册时携带默认权重 100，
 * 灵元 Bean 由各灵元自行注册时携带默认权重 0。
 * 路由层只认 weight 和方法资格，不引用实现方身份。
 */
public final class LingCoreConstants {

    /** 灵核进程级 lingId，Dashboard 识别灵核 baseline 用 */
    public static final String LINGCORE_LING_ID = "lingcore-app";

    /** 灵核实例版本号,永久不变 */
    public static final String LINGCORE_VERSION = "permanent";

    private LingCoreConstants() {
    }
}
