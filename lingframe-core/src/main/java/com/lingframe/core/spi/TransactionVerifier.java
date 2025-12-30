package com.lingframe.core.spi;

import java.lang.reflect.Method;

/**
 * 事务状态验证器 SPI
 * 用于解耦 Core 层与具体事务框架（Spring TX, JTA 等）
 */
public interface TransactionVerifier {
    /**
     * 判断目标方法是否声明了事务
     *
     * @param method      目标方法
     * @param targetClass 目标类（用于处理类级别注解）
     * @return true=需要同步执行以传播事务; false=可以异步隔离
     */
    boolean isTransactional(Method method, Class<?> targetClass);
}