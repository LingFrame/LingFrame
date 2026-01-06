package com.lingframe.core.config;

import com.lingframe.core.plugin.PluginRuntimeConfig;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.util.Collections;
import java.util.List;

/**
 * LingFrame Core 全局配置对象 (Immutable)
 * <p>
 * 职责：作为 Core 层的唯一配置入口，屏蔽 Spring Boot 或其他外部环境的差异。
 * 包含：
 * 1. 全局环境设置 (Environment)
 * 2. 运行时模板 (Runtime Template)
 */
@Data
@Builder
@ToString
public class LingFrameConfig {

    // ================= 全局环境 (Environment) =================

    private static volatile LingFrameConfig INSTANCE;

    /**
     * 获取全局配置实例 (静态方法，随处可调)
     */
    public static LingFrameConfig current() {
        if (INSTANCE == null) {
            // 兜底：如果没有初始化（比如单元测试），返回一个默认值，防止空指针
            return LingFrameConfig.builder().build();
        }
        return INSTANCE;
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

    // ================= 运行时模板 (Runtime Template) =================

    /**
     * 插件运行时的默认配置模板
     * (当创建新插件实例时，会应用此配置)
     */
    @Builder.Default
    private PluginRuntimeConfig runtimeConfig = PluginRuntimeConfig.defaults();

}