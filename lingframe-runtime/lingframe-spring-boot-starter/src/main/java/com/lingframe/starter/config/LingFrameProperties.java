package com.lingframe.starter.config;

import com.lingframe.api.security.AccessType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 灵珑框架主配置属性
 * <p>
 * 提供 IDE 智能提示和启动时校验。
 */
@Data
@Validated // [Key] 开启 JSR-303 校验
@ConfigurationProperties(prefix = "lingframe")
public class LingFrameProperties {

    /**
     * 是否启用灵珑框架。
     */
    private boolean enabled = true;

    /**
     * 开发模式开关。
     * 开启后将启用热重载监听器，并输出更多调试日志。
     */
    private boolean devMode = false;

    /**
     * 启动时是否自动扫描并加载 home 目录下的单元。
     */
    private boolean autoScan = true;

    /**
     * 单元存放根目录。
     * 支持绝对路径和相对路径。
     */
    private String lingHome = "Lings";

    /**
     * 预加载的 API JAR 文件路径列表
     * <p>
     * 这些 JAR 会在启动时加载到 SharedApiClassLoader 中，
     * 实现跨单元的 API 类共享
     * <p>
     * 路径支持：
     * - 绝对路径: /path/to/api.jar
     * - 相对路径: libs/order-api.jar (相对于 lingHome)
     */
    private List<String> preloadApiJars = new ArrayList<>();

    /**
     * 单元额外目录
     */
    private List<String> lingRoots = new ArrayList<>();

    /**
     * 服务注册排除包前缀列表
     * <p>
     * 用于隐式接口服务注册时，排除不需要注册的第三方框架接口。
     * 框架已内置常见排除规则（java.*, javax.*, io.micrometer.* 等），
     * 此配置用于扩展自定义排除项。
     * <p>
     * 示例：
     * 
     * <pre>
     * lingframe:
     *   service-excluded-packages:
     *     - com.custom.internal.
     *     - org.thirdparty.
     * </pre>
     */
    private List<String> serviceExcludedPackages = new ArrayList<>();

    /**
     * 灵核治理配置
     */
    private LingCoreGovernance lingCoreGovernance = new LingCoreGovernance();

    /**
     * 审计相关配置。
     */
    private Audit audit = new Audit();

    /**
     * 治理规则列表。
     * 用于配置具体的鉴权、流控和审计策略。
     */
    private List<GovernanceRule> rules = new ArrayList<>();

    /**
     * 统一管理内核运行时配置
     */
    private Runtime runtime = new Runtime();

    @Data
    public static class Audit {
        /**
         * 是否开启全局审计功能。
         */
        private boolean enabled = true;

        /**
         * 是否将审计日志输出到控制台。
         * 生产环境建议关闭，对接 Logstash 或 DB。
         */
        private boolean logConsole = true;

        /**
         * 异步审计线程池队列大小
         */
        private int queueSize = 1000;
    }

    /**
     * 单条治理规则定义
     */
    @Data
    public static class GovernanceRule {

        /**
         * 资源标识符匹配模式 (AntPath 风格)。
         * 示例: "com.example.*Service#delete*"
         */
        private String pattern;

        /**
         * 访问该资源所需的权限标识。
         * 示例: "order:delete"
         */
        private String permission;

        /**
         * 访问类型 (READ / WRITE / EXECUTE)。
         * 若不配置，内核将根据方法名自动推导。
         */
        private AccessType access;

        /**
         * 是否强制开启审计。
         * true: 强制开启; false: 强制关闭; null: 跟随全局配置或默认策略。
         */
        private Boolean audit;

        /**
         * 自定义审计动作名称。
         * 用于在审计日志中标记高危操作，如 "DANGEROUS_DELETE"。
         */
        private String auditAction;

        /**
         * 执行超时时间。
         * 默认 -1 表示不限制。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration timeout = Duration.ofMillis(-1);
    }

    @Data
    public static class Runtime {
        // --- 实例管理 ---
        private int maxHistorySnapshots = 5;

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration forceCleanupDelay = Duration.ofSeconds(30);

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration dyingCheckInterval = Duration.ofSeconds(5);

        // --- 调用控制 ---
        @DurationUnit(ChronoUnit.MILLIS)
        private Duration defaultTimeout = Duration.ofMillis(3000);

        private int bulkheadMaxConcurrent = 10;

        @DurationUnit(ChronoUnit.MILLIS)
        private Duration bulkheadAcquireTimeout = Duration.ofMillis(3000);

        // --- 单元线程池预算 ---

        /**
         * 全局单元线程总预算（所有单元共享此配额）
         */
        private int globalMaxLingThreads = java.lang.Runtime.getRuntime().availableProcessors() * 4;

        /**
         * 单个单元线程池硬上限
         */
        private int maxThreadsPerLing = 8;

        /**
         * 单个单元默认线程数
         */
        private int defaultThreadsPerLing = 2;
    }

    @Data
    public static class LingCoreGovernance {
        /**
         * 是否启用灵核 Bean 治理，默认值为 false
         * <p>
         * true: 启用治理，对灵核 Bean 进行权限检查和审计
         * <p>
         * false: 禁用治理，灵核 Bean 不受限制
         * <p>
         * 注意：开启后可能会影响单元的正常运行，建议仅在必要时开启
         */
        private boolean enabled = false;

        /**
         * 是否对灵核内部调用进行治理，默认值为 false
         * <p>
         * true: 灵核自己调用自己的 Bean 也会被治理
         * <p>
         * false: 只有单元调用灵核 Bean 时才会被治理
         * <p>
         * 注意：开启后可能会影响单元的正常运行，建议仅在必要时开启
         */
        private boolean governInternalCalls = false;

        /**
         * 是否对灵核应用进行权限检查，默认值为 false
         * <p>
         * true: 灵核应用也需要通过权限检查
         * <p>
         * false: 灵核应用自动拥有所有权限
         * <p>
         * 注意：开启后可能会影响单元的正常运行，建议仅在必要时开启
         */
        private boolean checkPermissions = false;
    }

}