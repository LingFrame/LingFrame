package com.lingframe.core.invoker;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.api.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/**
 * 基于 MethodHandle 的高性能调用器
 */
@Slf4j
public class FastLingServiceInvoker implements LingServiceInvoker {

    @Override
    public Object invoke(LingInstance instance, Object bean, Method method, Object[] args) throws Exception {
        long invocationId = instance.beginInvocation(ActiveInvocationSupport.capture(instance, method.getName()));
        if (invocationId < 0) {
            throw new ServiceUnavailableException(instance.getLingId(),
                    "Ling instance is not ready or already destroyed");
        }
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader targetClassLoader = instance.getClassLoader();

        try {
            Thread.currentThread().setContextClassLoader(targetClassLoader);
            return method.invoke(bean, args);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            instance.completeInvocation(invocationId);
        }
    }

    /**
     * 🚀 新增的高性能入口
     */
    public Object invokeFast(LingInstance instance, MethodHandle methodHandle, Object[] args) throws Throwable {
        long invocationId = instance.beginInvocation(ActiveInvocationSupport.capture(instance, "method-handle"));
        if (invocationId < 0) {
            throw new ServiceUnavailableException(instance.getLingId(),
                    "Ling instance is not ready or already destroyed");
        }
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader targetClassLoader = instance.getClassLoader();

        try {
            Thread.currentThread().setContextClassLoader(targetClassLoader);

            // `MethodHandle.invokeWithArguments` 会自动处理装箱、拆箱和参数数组展开
            return methodHandle.invokeWithArguments(args);

        } catch (Throwable e) {
            // `MethodHandle` 抛出的是 `Throwable`，这里保持向上透传
            throw e;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            instance.completeInvocation(invocationId);
        }
    }
}
