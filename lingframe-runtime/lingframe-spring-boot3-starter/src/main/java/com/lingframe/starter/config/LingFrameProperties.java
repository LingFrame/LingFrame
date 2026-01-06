package com.lingframe.starter.config;

import com.lingframe.api.security.AccessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
     * 插件存放根目录。
     * 支持绝对路径和相对路径。
     */
    private String pluginHome = "plugins";

    /**
     * 插件额外目录
     */
    private List<String> pluginRoots = new ArrayList<>();

    /**
     * 启动时是否自动扫描并加载 home 目录下的插件。
     */
    private boolean autoScan = true;

    /**
     * 宿主治理配置
     */
    @Valid
    private HostGovernance hostGovernance = new HostGovernance();

    /**
     * 审计相关配置。
     */
    @Valid // [Key] 级联校验
    private Audit audit = new Audit();

    /**
     * 治理规则列表。
     * 用于配置具体的鉴权、流控和审计策略。
     */
    @Valid // [Key] 级联校验 List 里的每个元素
    private List<GovernanceRule> rules = new ArrayList<>();

    /**
     * 统一管理内核运行时配置
     */
    @Valid
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
        @NotEmpty(message = "治理规则的 pattern 不能为空")
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
    }

    @Data
    public static class HostGovernance {
        /**
         * 是否启用宿主 Bean 治理，默认值为 false
         * <p>
         * true: 启用治理，对宿主 Bean 进行权限检查和审计
         * <p>
         * false: 禁用治理，宿主 Bean 不受限制
         * <p>
         * 注意：开启后可能会影响插件的正常运行，建议仅在必要时开启
         */
        private boolean enabled = false;

        /**
         * 是否对宿主内部调用进行治理，默认值为 false
         * <p>
         * true: 宿主自己调用自己的 Bean 也会被治理
         * <p>
         * false: 只有插件调用宿主 Bean 时才会被治理
         * <p>
         * 注意：开启后可能会影响插件的正常运行，建议仅在必要时开启
         */
        private boolean governInternalCalls = false;

        /**
         * 是否对宿主应用进行权限检查，默认值为 false
         * <p>
         * true: 宿主应用也需要通过权限检查
         * <p>
         * false: 宿主应用自动拥有所有权限
         * <p>
         * 注意：开启后可能会影响插件的正常运行，建议仅在必要时开启
         */
        private boolean checkPermissions = false;
    }

}