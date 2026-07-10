package com.lingframe.api.annotation;

import java.lang.annotation.*;

/**
 * 灵珑服务引用
 * 用于注入跨灵元提供的服务接口。
 * 示例：
 * <pre>
 *
 * {@code @LingReference}
 * {@code private UserService userService;}
 *
 * {@code @LingReference(lingId = "user-ling")}
 * {@code private UserService userService;}
 *
 * {@code @LingReference(serviceId = "lingcore:authService")}
 * {@code private AuthService auth;}
 * </pre>
 * <p>
 * 边界：本注解仅声明契约（路由锚点），不承载治理参数。
 * 超时、降级、重试等治理配置统一由灵核 YAML 与流水线负责。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface LingReference {

    /**
     * 指定灵元 ID（可选）
     * 如果不指定，框架会在所有已安装的灵元中查找实现了该接口的 Bean。
     */
    String lingId() default "";

    /**
     * 指定服务协议短 ID 或 FQSID（可选）
     * <p>
     * 适用场景：同一灵元对同一接口提供了多个实现（多版本/多实例），仅靠接口类型无法唯一定位时，
     * 用此属性显式锚定具体服务。
     * <ul>
     *   <li>填短 ID（如 "sendSms"）：解析为 FQSID = [lingId]:[serviceId]，lingId 留空时退化为 [当前灵元]:[serviceId]</li>
     *   <li>填完整 FQSID（如 "user-ling:sendSms"）：lingId 属性将被忽略</li>
     *   <li>留空（默认）：仅按字段类型解析，由全局路由自动匹配</li>
     * </ul>
     *
     * @return 服务 ID 或 FQSID，默认空表示仅按类型路由
     */
    String serviceId() default "";
}
