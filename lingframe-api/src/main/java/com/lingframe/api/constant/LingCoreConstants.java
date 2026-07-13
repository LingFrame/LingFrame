package com.lingframe.api.constant;

/**
 * 灵核作为 CORE provider 的标识常量。
 * <p>
 * 灵核 lingId 固定为 {@value #LINGCORE_LING_ID}，version 固定为 {@value #LINGCORE_VERSION}，
 * 不支持热加载/热卸载，随进程生死。
 * <p>
 * 二维路由下，灵核与灵元平级共存于 provider 索引：
 * 灵核 Bean 由 {@code LingCoreServiceRegistrarProcessor} 注册为 {@code ProviderKind.CORE}（默认权重 100），
 * 灵元 Bean 由各灵元自行注册为 {@code ProviderKind.LING}（默认权重 0）。
 * <p>
 * 灵元通过 {@code @LingReference} 调用灵核 Bean 时，FQSID 走 {@code __provider__:contractId} 占位符路径，
 * 由 {@code ContractProviderRoutingFilter} 在 L0 阶段按 provider 权重选中 CORE provider，
 * 通过此 lingId 从 {@code LingRepository} 取到灵核 runtime。
 */
public final class LingCoreConstants {

    /** 灵核作为 CORE provider 的 lingId */
    public static final String LINGCORE_LING_ID = "lingcore-app";

    /** 灵核实例版本号,永久不变 */
    public static final String LINGCORE_VERSION = "permanent";

    private LingCoreConstants() {
    }
}
