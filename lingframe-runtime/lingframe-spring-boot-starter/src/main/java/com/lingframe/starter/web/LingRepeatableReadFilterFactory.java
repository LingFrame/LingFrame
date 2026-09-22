package com.lingframe.starter.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/**
 * 灵珑可重复读过滤器工厂（版本无关 SPI）。
 * <p>
 * 公共 starter 仅声明该 SPI 与共享常量，不绑定任何具体 Servlet API，也<strong>不</strong>
 * 在公共配置中注册 Filter。boot2/boot3 starter 提供类型化实现，并作为<strong>唯一注册点</strong>
 * 通过各自 {@code LingFrameAutoConfiguration} 的 {@code @Import} 装载。
 * <p>
 * 设计目的：
 * <ul>
 *   <li>避免公共模块用反射或动态代理探测 javax/jakarta</li>
 *   <li>版本差异收敛在各自栈 starter，保证单一注册与统一 order</li>
 *   <li>新栈扩展只需新增 starter 实现并在 AutoConfig 中 {@code @Import}</li>
 * </ul>
 */
public interface LingRepeatableReadFilterFactory {

    /**
     * 创建一个符合当前 Servlet 栈的 {@link FilterRegistrationBean}。
     * <p>
     * 实现应基于 {@link #newRegistration()} 设置 order/name/url，再填入类型化 Filter。
     *
     * @return 当前栈的 FilterRegistrationBean
     */
    FilterRegistrationBean<?> createFilterRegistration();

    /**
     * 返回该工厂所适配的 Servlet API 包名（如 {@code "javax.servlet"} 或 {@code "jakarta.servlet"}）。
     * 主要用于诊断日志，不应承担运行时分支判断。
     *
     * @return Servlet API 包名
     */
    String servletApiPackage();

    /**
     * 共享常量：Filter 名称与 URL pattern。
     * 各栈实现使用这些常量构造 RegistrationBean，保证两栈注册行为一致。
     */
    String FILTER_NAME = "lingRepeatableReadFilter";
    String URL_PATTERN = "/*";

    /**
     * 共享工厂方法：使用 {@link Ordered#HIGHEST_PRECEDENCE} 构造 RegistrationBean 默认配置。
     * 各栈实现通过此方法获取配置一致的 RegistrationBean 骨架，仅需设置 Filter 实例即可。
     */
    default FilterRegistrationBean<?> newRegistration() {
        FilterRegistrationBean<?> registration = new FilterRegistrationBean<>();
        registration.addUrlPatterns(URL_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName(FILTER_NAME);
        return registration;
    }
}

