package com.lingframe.api.context;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.security.PermissionService;
import java.util.Optional;

/**
 * 单元上下文
 * 提供单元运行时的环境信息和能力获取入口
 * 
 * @author LingFrame
 */
public interface LingContext {
    
    /**
     * 获取当前单元的唯一标识
     * @return 单元ID
     */
    String getLingId();

    /**
     * 获取应用配置
     * @param key 配置键
     * @return 配置值
     */
    Optional<String> getProperty(String key);

    /**
     * 通用服务调用
     * 遵循面向协议原则，通过服务 ID (FQSID) 调用外部能力。
     * Core 会拦截并进行权限检查、审计和路由转发。
     * @param serviceId FQSID (ling ID:Short ID) 或 Client SDK 提供的常量
     * @param args 参数列表
     * @return 服务执行结果
     */
    <T> Optional<T> invoke(String serviceId, Object... args);

    /**
     * 获取系统服务或能力
     * <p>
     * 遵循零信任原则，业务单元只能通过此方法获取被 Core 授权的基础设施能力。
     * </p>
     * @param serviceClass 服务接口类
     * @return 服务实例
     */
    <T> Optional<T> getService(Class<T> serviceClass);

    /**
     * 获取 Core 提供的权限服务
     * @return 权限服务实例
     */
    PermissionService getPermissionService();

    /**
     * 发布事件
     * @param event 事件对象
     */
    void publishEvent(LingEvent event);
}
