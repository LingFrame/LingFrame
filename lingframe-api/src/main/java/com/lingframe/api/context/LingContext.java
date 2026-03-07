package com.lingframe.api.context;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.security.PermissionService;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 灵元上下文
 * 提供灵元运行时的环境信息和能力获取入口
 * 
 * @author LingFrame
 */
public interface LingContext {

    /**
     * 获取当前灵元的唯一标识
     * 
     * @return 灵元ID
     */
    String getLingId();

    /**
     * 获取应用配置
     * 
     * @param key 配置键
     * @return 配置值
     */
    Optional<String> getProperty(String key);

    /**
     * 通用服务调用
     * 遵循面向协议原则，通过服务 ID (FQSID) 调用外部能力。
     * Core 会拦截并进行权限检查、审计和路由转发。
     * 
     * @param serviceId FQSID (ling ID:Short ID) 或 Client SDK 提供的常量
     * @param args      参数列表
     * @return 服务执行结果
     */
    <T> Optional<T> invoke(String serviceId, Object... args);

    /**
     * 通用服务调用（静态默认值降级）
     * 遇到熔断、限流或服务不可用等框架级异常时，直接丢弃异常并返回调用方指定的默认值。
     * 
     * @param serviceId    FQSID
     * @param defaultValue 当调用失败时的静默降级默认值
     * @param args         参数列表
     * @return 正常结果或降级默认值
     */
    <T> T invokeOrDefault(String serviceId, T defaultValue, Object... args);

    /**
     * 通用服务调用（闭包计算降级）
     * 遇到框架级异常时触发回调执行。延迟计算，适用于在失败时才去进行查缓存、构建空对象等高消耗运算补偿。
     * 
     * @param serviceId        FQSID
     * @param fallbackSupplier 失败时执行的降级逻辑闭包
     * @param args             参数列表
     * @return 正常结果或降级逻辑计算的兜底结果
     */
    <T> T invokeOrElse(String serviceId, Supplier<T> fallbackSupplier, Object... args);

    /**
     * 获取系统服务或能力
     * <p>
     * 遵循零信任原则，业务灵元只能通过此方法获取被 Core 授权的基础设施能力。
     * </p>
     * 
     * @param serviceClass 服务接口类
     * @return 服务实例
     */
    <T> Optional<T> getService(Class<T> serviceClass);

    /**
     * 获取 Core 提供的权限服务
     * 
     * @return 权限服务实例
     */
    PermissionService getPermissionService();

    /**
     * 发布事件
     * 
     * @param event 事件对象
     */
    void publishEvent(LingEvent event);
}
