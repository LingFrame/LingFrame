package com.lingframe.core.spi;

import com.lingframe.core.plugin.PluginInstance;

import java.lang.reflect.Method;

/**
 * SPI: 插件服务调用器
 * 职责：封装具体的调用语义（反射、TCCL切换、异常处理）
 */
public interface PluginServiceInvoker {
    Object invoke(PluginInstance instance, Object bean, Method method, Object[] args) throws Exception;
}