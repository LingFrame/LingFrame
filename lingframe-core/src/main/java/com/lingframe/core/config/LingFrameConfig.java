package com.lingframe.core.config;

import com.lingframe.core.plugin.PluginRuntimeConfig;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LingFrame Core 全局配置对象 (Immutable)
 * <p>
 * 职责：作为 Core 层的唯一配置入口，屏蔽 Spring Boot 或其他外部环境的差异。
 * 包含：
 * 1. 全局环境设置 (Environment)
 * 2. 运行时模板 (Runtime Template)
 * 3. 跨 ClassLoader 配置
 */
@Data
@Builder
@ToString
public class LingFrameConfig {

    // ================= 全局环境 (Environment) =================

    private static volatile LingFrameConfig INSTANCE;

    // 默认配置（懒加载单例，线程安全 - Lazy Holder 模式）
    private static final class DefaultConfigHolder {
        static final LingFrameConfig DEFAULT = LingFrameConfig.builder().build();
    }

    /**
     * 获取全局配置实例 (静态方法，随处可调)
     * <p>
     * 线程安全说明：如果未初始化，返回线程安全的默认配置单例
     */
    public static LingFrameConfig current() {
        LingFrameConfig config = INSTANCE;
        if (config == null) {
            // 未初始化时返回固定的默认配置，保证行为一致性
            return DefaultConfigHolder.DEFAULT;
        }
        return config;
    }

    /**
     * 初始化全局实例 (由 Starter 启动时调用一次)
     */
    public static void init(LingFrameConfig config) {
        INSTANCE = config;
    }

    /**
     * 清理全局配置
     * 场景：单元测试 teardown
     */
    public static void clear() {
        INSTANCE = null;
    }

    /**
     * 是否开启开发模式 (影响热重载、日志等级、各类检查的宽松度)
     */
    @Builder.Default
    private boolean devMode = false;

    /**
     * 启动时是否自动扫描并加载 home 目录下的插件。
     */
    @Builder.Default
    private boolean autoScan = true;

    /**
     * 插件存放根目录
     */
    @Builder.Default
    private String pluginHome = "plugins";

    /**
     * 插件额外目录
     */
    @Builder.Default
    private List<String> pluginRoots = Collections.emptyList();

    /**
     * 核心线程数 (用于 PluginManager 的后台调度器)
     */
    @Builder.Default
    private int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());

    // ================= 宿主治理配置 =================

    /**
     * 是否启用宿主 Bean 治理，默认值为 false
     * <p>
     * true: 启用治理，对宿主 Bean 进行权限检查和审计
     * <p>
     * false: 禁用治理，宿主 Bean 不受限制
     */
    @Builder.Default
    private boolean hostGovernanceEnabled = false;

    /**
     * 是否对宿主内部调用进行治理，默认值为 false
     * <p>
     * true: 宿主自己调用自己的 Bean 也会被治理
     * <p>
     * false: 只有插件调用宿主 Bean 时才会被治理
     */
    @Builder.Default
    private boolean hostGovernanceInternalCalls = false;

    /**
     * 是否对宿主应用进行权限检查，默认值为 false
     * <p>
     * true: 宿主应用也需要通过权限检查
     * <p>
     * false: 宿主应用自动拥有所有权限
     */
    @Builder.Default
    private boolean hostCheckPermissions = false;

    // ================= 共享 API 配置 =================

    /**
     * 预加载的 API JAR 文件路径列表
     * <p>
     * 这些 JAR 会在启动时加载到 SharedApiClassLoader 中，
     * 实现跨插件的 API 类共享
     * <p>
     * 路径支持：
     * - 绝对路径: /path/to/api.jar
     * - 相对路径: libs/order-api.jar (相对于 pluginHome)
     * - Maven 模块: lingframe-examples/lingframe-example-order-api (开发模式)
     */
    @Builder.Default
    private List<String> preloadApiJars = new ArrayList<>();

    // ================= 运行时模板 (Runtime Template) =================

    /**
     * 插件运行时的默认配置模板
     * (当创建新插件实例时，会应用此配置)
     */
    @Builder.Default
    private PluginRuntimeConfig runtimeConfig = PluginRuntimeConfig.defaults();

}