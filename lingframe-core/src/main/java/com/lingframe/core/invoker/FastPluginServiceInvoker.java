package com.lingframe.core.invoker;

import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.spi.PluginServiceInvoker;
import com.lingframe.core.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/**
 * 基于 MethodHandle 的高性能调用器
 */
@Slf4j
public class FastPluginServiceInvoker implements PluginServiceInvoker {

    @Override
    public Object invoke(PluginInstance instance, Object bean, Method method, Object[] args) throws Exception {
        return method.invoke(bean, args);
    }

    /**
     * 🚀 新增的高性能入口
     */
    public Object invokeFast(PluginInstance instance, MethodHandle methodHandle, Object[] args) throws Throwable {
        if (!instance.tryEnter()) {
            throw new ServiceUnavailableException(instance.getPluginId(),
                    "Plugin instance is not ready or already destroyed");
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