package com.lingframe.api.annotation;

import java.lang.annotation.*;

/**
 * 灵珑服务定义
 * 标记在方法上，声明这是一个对外暴露的能力。
 * Core 将此注解作为 RPC 协议的契约和路由的关键。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LingService {

    /**
     * 服务协议 短 ID (必填)
     * Core 会自动与单元 ID 拼接为 FQSID: [ling ID]:[短 ID]
     * 保证了服务的全球唯一性，解决了 ID 冲突问题。
     *
     * @return 短 ID，例如 "send_sms"
     */
    String id();

    /**
     * 服务描述 (用于生成文档和审计日志)
     *
     * @return 描述
     */
    String desc() default "";

    /**
     * 超时时间 (毫秒，Client 端默认配置)
     *
     * @return 超时时间，默认 3000ms
     */
    long timeout() default 3000;
}