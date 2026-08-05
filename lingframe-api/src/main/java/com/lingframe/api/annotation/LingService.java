package com.lingframe.api.annotation;

import java.lang.annotation.*;

/**
 * 灵珑服务定义
 * 标记在方法或类型上，声明这是一个对外暴露的能力。
 * 灵核将此注解作为 RPC 协议契约和路由分发的关键依据。
 * <p>
 * 边界：本注解仅声明契约（服务 ID 与描述），不承载治理参数。
 * 超时、降级、重试等治理配置统一由灵核 YAML 与流水线负责。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LingService {

    /**
     * 服务协议短 ID（可选）
     * 灵核会自动与灵元 ID 拼接为 FQSID：[灵元 ID]:[短 ID]
     * 保证了服务的全球唯一性，解决了 ID 冲突问题。
     * <p>
     * 若留空（默认），灵核在注册时按以下规则推导短 ID：
     * <ul>
     *   <li>标注在类型上：取类型 SimpleName 的首字母小写形式（如 UserService -> userService）</li>
     *   <li>标注在方法上：取方法名</li>
     * </ul>
     *
     * @return 短 ID，例如 "send_sms"；默认空表示由灵核推导
     */
    String id() default "";

    /**
     * 服务描述 (用于生成文档和审计日志)
     *
     * @return 描述
     */
    String desc() default "";
}
