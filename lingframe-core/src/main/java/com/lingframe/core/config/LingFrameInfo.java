package com.lingframe.core.config;

/**
 * 灵核全局配置只读门面。
 * <p>
 * 替代 {@code LingFrameConfig.current()} 静态穿透，让外围模块（dashboard / 灵核）
 * 通过构造器注入拿到只读配置视图，不再依赖静态单例——便于测试 mock、避免生产代码随处静态调用。
 * <p>
 * 设计补全理由：注入式配置访问是灵核自身需要的设计补全，
 * 非为外围模块定制（判断标准：没有外围模块，灵核自己也该有这个注入式接口）。
 *
 * @see LingFrameConfig
 */
public interface LingFrameInfo {

    /**
     * 是否开启开发模式（影响热重载、日志等级、各类检查的宽松度）。
     */
    boolean isDevMode();

    /**
     * 灵元存放根目录。
     */
    String getLingHome();

    /**
     * 灵元运行时默认超时（毫秒）。
     */
    int getDefaultTimeout();

    /**
     * 是否开启隐式接口注册（灵元装载扫描策略开关）。
     * <p>
     * 开启时灵元装载器自动扫描并注册标注了治理注解的接口为灵元服务；
     * 关闭时要求显式声明，避免误扫框架接口。属灵元装载语义，非治理语义。
     *
     * @return true 表示开启隐式注册（默认）
     */
    boolean isImplicitRegistration();

    /**
     * 是否对灵核身份调用开启权限表校验。
     * <p>
     * 关闭时（默认）灵核身份 caller 豁免灵元权限表，走灵核审计边界守护；
     * 开启时灵核身份 caller 也走权限表校验，与 {@code LingFrameConfig.isLingCoreCheckPermissions()}
     * 和 {@code DefaultPermissionService} 的豁免条件对齐——这是生产加固 toggle，
     * 操作员设 {@code ling-core-governance.check-permissions: true} 即 enforce。
     *
     * @return true 表示灵核身份调用也走权限表校验
     */
    boolean isLingCoreCheckPermissions();
}
