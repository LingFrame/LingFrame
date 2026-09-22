package com.lingframe.api.resilience;

/**
 * 降级策略接口（SPI）。
 * <p>
 * 当灵元不可用（熔断打开、限流拒绝等）时，框架调用此接口提供降级响应，
 * 而非直接抛出异常。实现类可通过 SPI 或 Spring Bean 注册。
 * <p>
 * 典型实现：
 * <ul>
 *   <li>缓存兜底 —— 返回上次成功的缓存结果</li>
 *   <li>默认值 —— 返回安全的默认响应</li>
 *   <li>备用路由 —— 转发到备用灵元</li>
 * </ul>
 */
@FunctionalInterface
public interface FallbackProvider {

    /**
     * 提供降级响应。
     *
     * @param fqsid   全限定服务标识
     * @param cause   降级触发原因
     * @return 降级结果，返回 null 表示无法降级，框架将抛出原始异常
     * @throws Throwable 如果降级过程本身出错
     */
    Object fallback(String fqsid, FallbackCause cause) throws Throwable;
}
