package com.lingframe.starter.spi;

import com.lingframe.core.ling.LingInstance;
import java.lang.reflect.Method;

/**
 * SPI: 外挂式全链路调用拦截处理器。
 * <p>
 * 基于底座原生的 {@link com.lingframe.core.spi.LingServiceInvoker} 进行一层包装。
 * 允许在实际执行方法之前植入 TracerId，或者记录 RT 性能指标。
 */
public interface LingInvocationFilter {

    /**
     * 优先级顺序。数值越小优先级越高。
     */
    int getOrder();

    /**
     * 拦截跨单元调用
     *
     * @param instance 组件实例
     * @param bean     目标Bean
     * @param method   目标方法
     * @param args     实际参数
     * @param chain    责任链推进器，必须显式调用 {@code chain.proceed(...)}
     * @return 方法的实际执行结果
     * @throws Exception 调用抛出的异常
     */
    Object filter(LingInstance instance, Object bean, Method method, Object[] args, FilterChain chain) throws Exception;

    interface FilterChain {
        Object proceed(LingInstance instance, Object bean, Method method, Object[] args) throws Exception;
    }
}
