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
        return method.invoke(bean, args);
    }

    /**
     * 🚀 新增的高性能入口
     */
    public Object invokeFast(LingInstance instance, MethodHandle methodHandle, Object[] args) throws Throwable {
        if (!instance.tryEnter()) {
            throw new ServiceUnavailableException(instance.getLingId(),
                    "Ling instance is not ready or already destroyed");
        }
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();

        try {
            Thread.currentThread().setContextClassLoader(instance.getContainer().getClassLoader());

            // MethodHandle.invokeWithArguments 会自动处理装箱/拆箱和参数数组展开
            return methodHandle.invokeWithArguments(args);

        } catch (Throwable e) {
            // MethodHandle 抛出的是 Throwable，需要转换
            throw e;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            instance.exit();
        }
    }
}