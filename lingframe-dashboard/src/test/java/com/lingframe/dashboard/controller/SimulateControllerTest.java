package com.lingframe.dashboard.controller;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.SimulateResultDTO;
import com.lingframe.dashboard.dto.StressResultDTO;
import com.lingframe.dashboard.service.SimulateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模拟控制器测试
 * 覆盖 simulateResource / simulateIpc / updateDevMode / stressTest 四个端点
 * 含 stressTest 的三路异常分支（LingNotFound / LingInvocation / 其他）
 */
@DisplayName("模拟控制器测试")
class SimulateControllerTest {

    private SimulateService simulateService;
    private SimulateController controller;

    @BeforeEach
    void setUp() {
        simulateService = mock(SimulateService.class);
        controller = new SimulateController(simulateService);
    }

    @Nested
    @DisplayName("simulateResource")
    class SimulateResourceTests {

        @Test
        @DisplayName("正常返回模拟结果")
        void shouldReturnResult() {
            SimulateResultDTO result = SimulateResultDTO.builder().build();
            when(simulateService.simulateResource("ling1", "dbRead")).thenReturn(result);

            SimulateController.ResourceRequest request = new SimulateController.ResourceRequest();
            request.setResourceType("dbRead");
            ApiResponse<SimulateResultDTO> response = controller.simulateResource("ling1", request);

            assertTrue(response.isSuccess());
            assertSame(result, response.getData());
        }

        @Test
        @DisplayName("service 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            when(simulateService.simulateResource("ling1", "dbRead"))
                    .thenThrow(new RuntimeException("boom"));

            SimulateController.ResourceRequest request = new SimulateController.ResourceRequest();
            request.setResourceType("dbRead");
            ApiResponse<SimulateResultDTO> response = controller.simulateResource("ling1", request);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("模拟失败"));
        }
    }

    @Nested
    @DisplayName("simulateIpc")
    class SimulateIpcTests {

        @Test
        @DisplayName("正常返回 IPC 模拟结果")
        void shouldReturnResult() {
            SimulateResultDTO result = SimulateResultDTO.builder().build();
            when(simulateService.simulateIpc("ling1", "ling2", true)).thenReturn(result);

            SimulateController.IpcRequest request = new SimulateController.IpcRequest();
            request.setTargetLingId("ling2");
            request.setIpcEnabled(true);
            ApiResponse<SimulateResultDTO> response = controller.simulateIpc("ling1", request);

            assertTrue(response.isSuccess());
            assertSame(result, response.getData());
        }

        @Test
        @DisplayName("service 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            when(simulateService.simulateIpc(eq("ling1"), anyString(), anyBoolean()))
                    .thenThrow(new RuntimeException("ipc fail"));

            SimulateController.IpcRequest request = new SimulateController.IpcRequest();
            request.setTargetLingId("ling2");
            request.setIpcEnabled(false);
            ApiResponse<SimulateResultDTO> response = controller.simulateIpc("ling1", request);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("IPC 模拟失败"));
        }
    }

    @Nested
    @DisplayName("updateDevMode")
    class UpdateDevModeTests {

        @Test
        @DisplayName("testEnv=dev 应切换到 DEV 模式并返回 true")
        void shouldSwitchToDevMode() {
            boolean original = LingFrameConfig.current().isDevMode();
            try {
                SimulateController.ModeRequest request = new SimulateController.ModeRequest();
                request.setTestEnv("dev");
                ApiResponse<Boolean> response = controller.updateDevMode(request);

                assertTrue(response.isSuccess());
                assertTrue(response.getData());
                assertTrue(LingFrameConfig.current().isDevMode());
            } finally {
                LingFrameConfig.current().setDevMode(original);
            }
        }

        @Test
        @DisplayName("testEnv=prod 应切换到 PROD 模式并返回 false")
        void shouldSwitchToProdMode() {
            boolean original = LingFrameConfig.current().isDevMode();
            try {
                LingFrameConfig.current().setDevMode(true);
                SimulateController.ModeRequest request = new SimulateController.ModeRequest();
                request.setTestEnv("prod");
                ApiResponse<Boolean> response = controller.updateDevMode(request);

                assertTrue(response.isSuccess());
                assertFalse(response.getData());
                assertFalse(LingFrameConfig.current().isDevMode());
            } finally {
                LingFrameConfig.current().setDevMode(original);
            }
        }

        @Test
        @DisplayName("testEnv 大小写不敏感，DEV 等同 dev")
        void shouldBeCaseInsensitive() {
            boolean original = LingFrameConfig.current().isDevMode();
            try {
                SimulateController.ModeRequest request = new SimulateController.ModeRequest();
                request.setTestEnv("DEV");
                ApiResponse<Boolean> response = controller.updateDevMode(request);

                assertTrue(response.isSuccess());
                assertTrue(response.getData());
            } finally {
                LingFrameConfig.current().setDevMode(original);
            }
        }

        @Test
        @DisplayName("testEnv 为 null 时应返回 false（PROD 模式）")
        void shouldReturnFalseForNullEnv() {
            boolean original = LingFrameConfig.current().isDevMode();
            try {
                SimulateController.ModeRequest request = new SimulateController.ModeRequest();
                request.setTestEnv(null);
                ApiResponse<Boolean> response = controller.updateDevMode(request);

                assertTrue(response.isSuccess());
                assertFalse(response.getData());
            } finally {
                LingFrameConfig.current().setDevMode(original);
            }
        }
    }

    @Nested
    @DisplayName("stressTest")
    class StressTestTests {

        @Test
        @DisplayName("正常返回压测结果")
        void shouldReturnResult() {
            StressResultDTO result = StressResultDTO.builder().build();
            when(simulateService.stressTest("ling1")).thenReturn(result);

            ApiResponse<StressResultDTO> response = controller.stressTest("ling1");

            assertTrue(response.isSuccess());
            assertSame(result, response.getData());
        }

        @Test
        @DisplayName("LingNotFoundException 应返回 '灵元已缺失或不可用'")
        void shouldHandleLingNotFound() {
            when(simulateService.stressTest("ling1"))
                    .thenThrow(new LingNotFoundException("ling1"));

            ApiResponse<StressResultDTO> response = controller.stressTest("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("灵元已缺失或不可用"));
        }

        @Test
        @DisplayName("LingInvocationException 应返回 '灵元已缺失或不可用'")
        void shouldHandleLingInvocation() {
            when(simulateService.stressTest("ling1"))
                    .thenThrow(new LingInvocationException(
                            "ling1", LingInvocationException.ErrorKind.INVOKE_ERROR));

            ApiResponse<StressResultDTO> response = controller.stressTest("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("灵元已缺失或不可用"));
        }

        @Test
        @DisplayName("其他异常应返回 '压测失败'")
        void shouldHandleGenericException() {
            when(simulateService.stressTest("ling1"))
                    .thenThrow(new RuntimeException("unexpected"));

            ApiResponse<StressResultDTO> response = controller.stressTest("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("压测失败"));
        }
    }
}
