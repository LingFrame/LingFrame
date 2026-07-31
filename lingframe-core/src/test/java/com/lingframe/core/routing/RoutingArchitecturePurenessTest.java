package com.lingframe.core.routing;

import com.lingframe.core.spi.RoutableTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("路由架构与核心 SPI 纯洁性测试")
class RoutingArchitecturePurenessTest {

    @Test
    @DisplayName("RoutableTarget 核心 SPI 接口绝不泄露特定的 canary 方法")
    void routableTargetSpiShouldBePureWithoutCanaryMethods() {
        Method[] methods = RoutableTarget.class.getDeclaredMethods();
        for (Method method : methods) {
            String name = method.getName().toLowerCase();
            assertFalse(name.contains("canary"),
                    "RoutableTarget SPI 不允许泄露特定业务概念的方法: " + method.getName());
        }
    }
}
