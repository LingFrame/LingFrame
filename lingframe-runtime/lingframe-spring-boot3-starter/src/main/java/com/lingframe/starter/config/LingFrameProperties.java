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
    @NotEmpty(message = "插件目录不能为空")
    private String home = "plugins";

    /**
     * 启动时是否自动扫描并加载 home 目录下的插件。
     */
    private boolean autoScan = true;

    /**
     * 审计相关配置。
     */
    @Valid // [Key] 级联校验
    private Audit audit = new Audit();

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
     * 治理规则列表。
     * 用于配置具体的鉴权、流控和审计策略。
     */
    @Valid // [Key] 级联校验 List 里的每个元素
    private List<GovernanceRule> rules = new ArrayList<>();

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
}