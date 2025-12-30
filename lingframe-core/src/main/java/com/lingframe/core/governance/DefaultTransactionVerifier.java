package com.lingframe.core.governance;

import com.lingframe.core.spi.TransactionVerifier;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认事务验证器
 * 特性：
 * 1. 零依赖：通过类名字符串匹配，不依赖 Spring/Jakarta 包
 * 2. 高性能：结果缓存，避免重复反射
 * 3. 兼容性：同时支持 Spring @Transactional 和 javax/jakarta @Transactional
 */
@Slf4j
public class DefaultTransactionVerifier implements TransactionVerifier {

    // 缓存判断结果：Method -> Boolean
    private final Map<Method, Boolean> cache = new ConcurrentHashMap<>();

    // 目标注解的类名白名单
    private static final Set<String> TX_ANNOTATION_NAMES = Set.of(
            "org.springframework.transaction.annotation.Transactional", // Spring
            "javax.transaction.Transactional",                          // JTA (Old)
            "jakarta.transaction.Transactional"                         // JTA (New)
    );

    @Override
    public boolean isTransactional(Method method, Class<?> targetClass) {
        return cache.computeIfAbsent(method, m -> doCheck(m, targetClass));
    }

    private boolean doCheck(Method method, Class<?> targetClass) {
        // 1. 检查方法上的注解
        if (hasTxAnnotation(method.getAnnotations())) {
            return true;
        }

        // 2. 检查类上的注解 (Spring 允许类级别 @Transactional)
        if (hasTxAnnotation(targetClass.getAnnotations())) {
            return true;
        }

        // 3. (可选) 检查接口上的注解 - 虽然不推荐但可能存在
        for (Class<?> iface : targetClass.getInterfaces()) {
            if (hasTxAnnotation(iface.getAnnotations())) {
                return true;
            }
            try {
                Method ifaceMethod = iface.getMethod(method.getName(), method.getParameterTypes());
                if (hasTxAnnotation(ifaceMethod.getAnnotations())) {
                    return true;
                }
            } catch (NoSuchMethodException e) {
                // ignore
            }
        }

        return false;
    }

    private boolean hasTxAnnotation(Annotation[] annotations) {
        for (Annotation ann : annotations) {
            Class<? extends Annotation> type = ann.annotationType();
            // A. 直接匹配
            if (TX_ANNOTATION_NAMES.contains(type.getName())) {
                return true;
            }
            // B. (进阶) 支持元注解 (Meta-Annotation)
            // 例如用户自定义了 @MyTx 里面包含了 @Transactional
            // 注意：为了防止死循环和深度性能问题，这里只检查一层
            for (Annotation meta : type.getAnnotations()) {
                if (TX_ANNOTATION_NAMES.contains(meta.annotationType().getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}