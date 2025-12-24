package com.lingframe.core.plugin;

import com.lingframe.core.spi.PluginContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@Slf4j
class PluginLifecycleTest {

    @Test
    void testGracefulGcAfterUsage() throws InterruptedException {
        // 1. 初始化 Slot（使用单线程调度器）
        PluginSlot slot = new PluginSlot("test-plugin", Executors.newSingleThreadScheduledExecutor(), null, null);

        // 2. Mock 容器并设置打桩：必须让 isActive 返回 true，否则 destroy() 里的 stop() 不会执行
        PluginContainer container = mock(PluginContainer.class);
        when(container.isActive()).thenReturn(true); // 🔥 关键点

        PluginInstance inst = new PluginInstance("v1.0", container);

        // 3. 安装 v1.0 实例
        slot.addInstance(inst, null, true);

        // 4. 模拟请求进入（引用计数 +1）
        inst.enter();

        // 5. 触发版本升级（安装 v1.1），v1.0 会被移入死亡队列
        PluginInstance newInst = new PluginInstance("v1.1", mock(PluginContainer.class));
        slot.addInstance(newInst, null, true);

        // 验证 v1.0 状态：已濒死但非闲置
        assertTrue(inst.isDying(), "v1.0 应该被标记为 dying");
        assertFalse(inst.isIdle(), "v1.0 还有活跃请求，不应该是 idle");

        // 6. 模拟请求退出（引用计数归零）
        inst.exit();
        assertTrue(inst.isIdle(), "引用计数归零后应该是 idle");

        // 7. 等待定时任务执行 (PluginSlot 构造函数中设置的是每 5 秒执行一次 checkAndKill)
        // 为了确保测试稳定，我们多等 1 秒
        Thread.sleep(6000);

        // 8. 验证结果：container.stop() 最终被调用了
        verify(container, times(1)).stop();
        log.info("单元测试通过：实例已成功优雅回收");
    }
}