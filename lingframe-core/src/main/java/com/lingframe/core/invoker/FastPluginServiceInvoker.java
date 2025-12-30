// 新增文件：lingframe-core/src/main/java/com/lingframe/core/invoker/FastPluginServiceInvoker.java

package com.lingframe.core.invoker;

import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.spi.PluginServiceInvoker;
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
        // 兼容性接口：如果上层直接传了 Method，我们这里其实拿不到 MethodHandle
        // 所以我们需要在 PluginSlot 层面透传 MethodHandle，或者修改接口。
        // 为了不破坏 SPI 接口兼容性，建议在 PluginSlot 内部直接调用，或者扩展 SPI 接口。
        // 这里演示如果必须走 SPI，如何降级：
        return method.invoke(bean, args);
    }

    /**
     * 🚀 新增的高性能入口
     */
    public Object invokeFast(PluginInstance instance, MethodHandle methodHandle, Object[] args) throws Throwable {
        instance.enter();
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