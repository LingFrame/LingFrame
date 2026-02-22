package com.lingframe.core.config;

import com.lingframe.core.ling.LingRuntimeConfig;
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
        if (INSTANCE != null) {
            // 防止单元加载 Spring 上下文时覆盖灵核的全局配置
            // 例如：LINGCORE 已开启 DevMode，单元启动默认配置为 false，若覆盖则导致 DevMode 失效
            return;
        }
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
     * 启动时是否自动扫描并加载 home 目录下的单元。
     */
    @Builder.Default
    private boolean autoScan = true;

    /**
     * 单元存放根目录
     */
    @Builder.Default
    private String lingHome = "Lings";

    /**
     * 单元额外目录
     */
    @Builder.Default
    private List<String> lingRoots = Collections.emptyList();

    /**
     * 核心线程数 (用于 LingManager 的后台调度器)
     */
    @Builder.Default
    private int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());

    // ================= 单元线程池预算 =================

    /**
     * 全局单元线程总预算（所有单元共享此配额）
     * <p>
     * 每个单元创建独立线程池时，从此预算中扣减。
     * 卸载时归还。防止单元线程数不可控膨胀。
     */
    @Builder.Default
    private int globalMaxLingThreads = Runtime.getRuntime().availableProcessors() * 4;

    /**
     * 单个单元线程池硬上限
     * <p>
     * 即使单元 ling.yml 中配置了更高的值，也不会超过此上限。
     */
    @Builder.Default
    private int maxThreadsPerLing = 8;

    /**
     * 单个单元默认线程数
     * <p>
     * 当单元未在 ling.yml 中指定线程数时，使用此默认值。
     */
    @Builder.Default
    private int defaultThreadsPerLing = 2;

    // ================= 灵核治理配置 =================

    /**
     * 是否启用灵核 Bean 治理，默认值为 false
     * <p>
     * true: 启用治理，对灵核 Bean 进行权限检查和审计
     * <p>
     * false: 禁用治理，灵核 Bean 不受限制
     */
    @Builder.Default
    private boolean lingCoreGovernanceEnabled = false;

    /**
     * 是否对灵核内部调用进行治理，默认值为 false
     * <p>
     * true: 灵核自己调用自己的 Bean 也会被治理
     * <p>
     * false: 只有单元调用灵核 Bean 时才会被治理
     */
    @Builder.Default
    private boolean lingCoreGovernanceInternalCalls = false;

    /**
     * 是否对灵核应用进行权限检查，默认值为 false
     * <p>
     * true: 灵核应用也需要通过权限检查
     * <p>
     * false: 灵核应用自动拥有所有权限
     */
    @Builder.Default
    private boolean hostCheckPermissions = false;

    // ================= 共享 API 配置 =================

    /**
     * 预加载的 API JAR 文件路径列表
     * <p>
     * 这些 JAR 会在启动时加载到 SharedApiClassLoader 中，
     * 实现跨单元的 API 类共享
     * <p>
     * 路径支持：
     * - 绝对路径: /path/to/api.jar
     * - 相对路径: libs/order-api.jar (相对于 lingHome)
     * - Maven 单元: lingframe-examples/lingframe-example-order-api (开发模式)
     */
    @Builder.Default
    private List<String> preloadApiJars = new ArrayList<>();

    // ================= 运行时模板 (Runtime Template) =================

    /**
     * 单元运行时的默认配置模板
     * (当创建新单元实例时，会应用此配置)
     */
    @Builder.Default
    private LingRuntimeConfig runtimeConfig = LingRuntimeConfig.defaults();

}