package com.lingframe.core.spi;

import com.lingframe.core.ling.LingInstance;

import java.lang.reflect.Method;

/**
 * SPI: 单元服务调用器
 * 职责：封装具体的调用语义（反射、TCCL切换、异常处理）
 */
public interface LingServiceInvoker {
    Object invoke(LingInstance instance, Object bean, Method method, Object[] args) throws Exception;
}