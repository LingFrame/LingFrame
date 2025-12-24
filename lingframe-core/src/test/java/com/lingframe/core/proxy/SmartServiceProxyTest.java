package com.lingframe.core.proxy;

import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.plugin.PluginSlot;
import com.lingframe.core.spi.PluginContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmartServiceProxyTest {

    @Test
    @SuppressWarnings("unchecked")
    void testProxyInvokesCorrectInstance() throws Throwable {
        // 1. Mock 依赖
        PluginSlot slot = mock(PluginSlot.class);
        GovernanceKernel kernel = mock(GovernanceKernel.class);

        // 2. 核心修正：使用 java.util.function.Supplier 匹配 GovernanceKernel.java
        when(kernel.invoke(any(InvocationContext.class), any(Supplier.class))).thenAnswer(inv -> {
            // 获取第二个参数，即 Supplier<Object> executor
            Supplier<Object> executor = inv.getArgument(1);
            return executor.get(); // 执行代理逻辑
        });

        // 3. 准备 Proxy 运行环境
        SmartServiceProxy proxy = new SmartServiceProxy("caller-plugin", slot, Runnable.class, kernel, null);

        PluginInstance mockInst = mock(PluginInstance.class);
        PluginContainer mockContainer = mock(PluginContainer.class);

        // 模拟路由和 Bean 获取
        when(slot.selectInstance(any())).thenReturn(mockInst);
        when(mockInst.getContainer()).thenReturn(mockContainer);
        when(mockContainer.getBean(Runnable.class)).thenReturn((Runnable) () -> System.out.println("Execution success"));

        // 4. 执行调用
        Method runMethod = Runnable.class.getMethod("run");
        proxy.invoke(null, runMethod, new Object[0]);

        // 5. 验证生命周期闭环
        verify(mockInst, times(1)).enter(); // 必须调用进入计数
        verify(mockInst, times(1)).exit();  // 必须调用退出计数，否则内存泄漏
    }
}