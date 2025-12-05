package com.lingframe.api.context;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.security.PermissionService;
import java.util.Optional;

/**
 * 插件上下文
 * 提供插件运行时的环境信息和能力获取入口
 * 
 * @author LingFrame
 */
public interface PluginContext {
    
    /**
     * 获取当前插件的唯一标识
     * @return 插件ID
     */
    String getPluginId();

    /**
     * 获取应用配置
     * @param key 配置键
     * @return 配置值
     */
    Optional<String> getProperty(String key);

    /**
     * 获取系统服务或能力
     * <p>
     * 遵循零信任原则，业务插件只能通过此方法获取被 Core 授权的基础设施能力。
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
