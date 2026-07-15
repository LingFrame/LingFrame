package com.lingframe.dashboard.controller;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.core.runtime.SwitchableRuntimeMode;
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
 * 覆盖 simulateResource / simulateIpc / stressTest / updateMode 四个端点
 * 含 stressTest 的三路异常分支（LingNotFound / LingInvocation / 其他）
 * 含 updateMode 的密码认证与 fail-closed 场景（方向 B 重构）
 */
@DisplayName("模拟控制器测试")
class SimulateControllerTest {

    private SimulateService simulateService;
    private SimulateController controller;

    @BeforeEach
    void setUp() {
        simulateService = mock(SimulateService.class);
        // fail-closed 模式（未配置密码），测试不涉及模式切换
        controller = new SimulateController(simulateService, new SwitchableRuntimeMode(false, null));
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

    @Nested
    @DisplayName("updateMode")
    class UpdateModeTests {

        private static final String PASSWORD = "test-password";

        private SwitchableRuntimeMode runtimeMode;
        private SimulateController modeController;

        @BeforeEach
        void setUpMode() {
            // 每个测试用例使用全新的 RuntimeMode，避免失败计数累积触发锁定
            runtimeMode = new SwitchableRuntimeMode(false, PASSWORD);
            modeController = new SimulateController(simulateService, runtimeMode);
        }

        @Test
        @DisplayName("密码正确时切换到 dev 模式成功")
        void shouldSwitchToDevWithCorrectPassword() {
            SimulateController.ModeRequest request = new SimulateController.ModeRequest();
            request.setTestEnv("dev");
            request.setPassword(PASSWORD);

            ApiResponse<Boolean> response = modeController.updateMode(request);

            assertTrue(response.isSuccess());
            assertTrue(response.getData());
            assertTrue(runtimeMode.isDev());
        }

        @Test
        @DisplayName("密码正确时切换到 prod 模式成功")
        void shouldSwitchToProdWithCorrectPassword() {
            // 先切到 dev，再切回 prod
            SimulateController.ModeRequest devReq = new SimulateController.ModeRequest();
            devReq.setTestEnv("dev");
            devReq.setPassword(PASSWORD);
            modeController.updateMode(devReq);

            SimulateController.ModeRequest prodReq = new SimulateController.ModeRequest();
            prodReq.setTestEnv("prod");
            prodReq.setPassword(PASSWORD);

            ApiResponse<Boolean> response = modeController.updateMode(prodReq);

            assertTrue(response.isSuccess());
            assertFalse(response.getData());
            assertFalse(runtimeMode.isDev());
        }

        @Test
        @DisplayName("密码错误时返回 error")
        void shouldReturnErrorOnWrongPassword() {
            SimulateController.ModeRequest request = new SimulateController.ModeRequest();
            request.setTestEnv("dev");
            request.setPassword("wrong-password");

            ApiResponse<Boolean> response = modeController.updateMode(request);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("密码错误"));
        }

        @Test
        @DisplayName("未配置密码时 fail-closed 返回 error")
        void shouldFailClosedWhenPasswordNotConfigured() {
            // 未配置密码的 RuntimeMode：切换功能关闭
            SwitchableRuntimeMode closedMode = new SwitchableRuntimeMode(false, null);
            SimulateController closedController = new SimulateController(simulateService, closedMode);

            SimulateController.ModeRequest request = new SimulateController.ModeRequest();
            request.setTestEnv("dev");
            request.setPassword(PASSWORD);

            ApiResponse<Boolean> response = closedController.updateMode(request);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("未启用"));
        }

        @Test
        @DisplayName("testEnv 大小写不敏感：DEV → dev")
        void shouldBeCaseInsensitiveForDev() {
            SimulateController.ModeRequest request = new SimulateController.ModeRequest();
            request.setTestEnv("DEV");
            request.setPassword(PASSWORD);

            ApiResponse<Boolean> response = modeController.updateMode(request);

            assertTrue(response.isSuccess());
            assertTrue(response.getData());
            assertTrue(runtimeMode.isDev());
        }

        @Test
        @DisplayName("testEnv 大小写不敏感：PROD → prod")
        void shouldBeCaseInsensitiveForProd() {
            SimulateController.ModeRequest request = new SimulateController.ModeRequest();
            request.setTestEnv("PROD");
            request.setPassword(PASSWORD);

            ApiResponse<Boolean> response = modeController.updateMode(request);

            assertTrue(response.isSuccess());
            assertFalse(response.getData());
            assertFalse(runtimeMode.isDev());
        }
    }
}
