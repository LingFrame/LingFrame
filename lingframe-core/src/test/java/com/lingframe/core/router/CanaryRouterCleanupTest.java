package com.lingframe.core.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("CanaryRouter 清理测试")
public class CanaryRouterCleanupTest {

    @Test
    @DisplayName("移除金丝雀配置后应清空已存在配置")
    void removeCanaryConfigShouldClear() {
        CanaryRouter router = new CanaryRouter((candidates, context) -> null);
        router.setCanaryConfig("ling-a", 30, "1.2.3");

        assertEquals(30, router.getCanaryPercent("ling-a"));
        router.removeCanaryConfig("ling-a");

        assertEquals(0, router.getCanaryPercent("ling-a"));
        assertNull(router.getCanaryConfig("ling-a"));
    }
}
