package com.lingframe.dashboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("服务演练场能力开关测试")
class ServicePlaygroundToolGuardTest {

    @Test
    @DisplayName("真实调用未启用时应在进入业务链前拒绝")
    void realInvocationShouldBeRejectedWhenDisabled() {
        ServicePlaygroundService service = new ServicePlaygroundService(null, null, null, null, null, null);
        service.setRealInvocationEnabled(false);

        assertThrows(IllegalStateException.class, () -> service.invokeService(
                "ling1", "contract:method", "run", null, null, null, "SPECIFIED", false));
    }
}
