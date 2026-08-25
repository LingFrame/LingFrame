package com.lingframe.core.invoker;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.api.exception.LingInvocationException;
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
            throw new LingInvocationException(instance.getLingId(),
                    LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling instance is not ready or already destroyed");
        }
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader targetClassLoader = instance.getClassLoader();
        if (targetClassLoader == null) {
            instance.completeInvocation(invocationId);
            throw new LingInvocationException(instance.getLingId(),
                    LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling instance classloader is unavailable (likely unloaded or force-drained): "
                            + instance.getInstanceId());
        }

        try {
            Thread.currentThread().setContextClassLoader(targetClassLoader);
            Object[] finalArgs = ArgumentTypeAdapter.adapt(method, args, targetClassLoader);
            return method.invoke(bean, finalArgs);
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
            throw new LingInvocationException(instance.getLingId(),
                    LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling instance is not ready or already destroyed");
        }
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader targetClassLoader = instance.getClassLoader();
        if (targetClassLoader == null) {
            instance.completeInvocation(invocationId);
            throw new LingInvocationException(instance.getLingId(),
                    LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling instance classloader is unavailable (likely unloaded or force-drained): "
                            + instance.getInstanceId());
        }

        try {
            Thread.currentThread().setContextClassLoader(targetClassLoader);

            // 自适应强类型入参转换（零强引用残留）
            Object[] finalArgs = ArgumentTypeAdapter.adapt(methodHandle.type(), args, targetClassLoader);

            // MethodHandle.invokeWithArguments 会自动处理装箱、拆箱和参数数组展开
            return methodHandle.invokeWithArguments(finalArgs);

        } catch (Throwable e) {
            // `MethodHandle` 抛出的是 `Throwable`，这里保持向上透传
            throw e;
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            instance.completeInvocation(invocationId);
        }
    }
}