package com.lingframe.core.spi;

import com.lingframe.core.ling.LingInstance;

import java.lang.reflect.Method;

/**
 * SPI: 灵元服务调用器
 * 职责：封装具体的调用语义（反射、TCCL切换、异常处理）
 * <p>
 * 实现必须在业务执行前通过实例的 beginInvocation 登记准入，拒绝时不得执行，
 * 并在执行退出的 finally 中调用 completeInvocation。直接调用业务而跳过登记的自定义实现
 * 不具备框架提供的实例禁用与排空保证；不得用路由时的就绪检查替代最终准入。
 */
public interface LingServiceInvoker {
    Object invoke(LingInstance instance, Object bean, Method method, Object[] args) throws Exception;
}
