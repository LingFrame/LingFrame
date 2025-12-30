package com.lingframe.starter.adapter;

import com.lingframe.core.spi.TransactionVerifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

public class SpringTransactionVerifier implements TransactionVerifier {
    @Override
    public boolean isTransactional(Method method, Class<?> targetClass) {
        // 使用 Spring 强大的工具类，支持层级查找、元注解、别名等所有特性
        return AnnotatedElementUtils.hasAnnotation(method, Transactional.class) ||
                AnnotatedElementUtils.hasAnnotation(targetClass, Transactional.class);
    }
}