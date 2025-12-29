package com.lingframe.core.invoker;

import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.spi.PluginServiceInvoker;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Slf4j
public class DefaultPluginServiceInvoker implements PluginServiceInvoker {

    @Override
    public Object invoke(PluginInstance instance, Object bean, Method method, Object[] args) throws Exception {
        // 引用计数保护
        instance.enter();
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();

        try {
            // TCCL 切换
            Thread.currentThread().setContextClassLoader(instance.getContainer().getClassLoader());

            // 反射调用
            return method.invoke(bean, args);

        } catch (InvocationTargetException e) {
            // 透传业务异常
            Throwable target = e.getTargetException();
            if (target instanceof Exception) throw (Exception) target;
            throw new RuntimeException(target);
        } finally {
            // 资源恢复
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            instance.exit();
        }
    }
}