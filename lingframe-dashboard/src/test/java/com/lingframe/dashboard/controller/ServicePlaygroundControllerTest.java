package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.InvokeResultDTO;
import com.lingframe.dashboard.dto.ServiceMetadataDTO;
import com.lingframe.dashboard.service.ServicePlaygroundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.when;

/**
 * 服务演练场控制器测试
 * 覆盖 getServices 和 invokeService 端点的正常路径与异常路径
 */
@DisplayName("服务演练场控制器测试")
class ServicePlaygroundControllerTest {

    private ServicePlaygroundService playgroundService;
    private ServicePlaygroundController controller;

    @BeforeEach
    void setUp() {
        playgroundService = mock(ServicePlaygroundService.class);
        controller = new ServicePlaygroundController(playgroundService);
    }

    @Nested
    @DisplayName("getServices")
    class GetServicesTests {

        @Test
        @DisplayName("正常返回灵元的服务元数据列表")
        void shouldReturnServices() {
            ServiceMetadataDTO svc1 = new ServiceMetadataDTO();
            ServiceMetadataDTO svc2 = new ServiceMetadataDTO();
            when(playgroundService.getServices("ling1"))
                    .thenReturn(Arrays.asList(svc1, svc2));

            ApiResponse<List<ServiceMetadataDTO>> response = controller.getServices("ling1");

            assertTrue(response.isSuccess());
            assertEquals(2, response.getData().size());
        }

        @Test
        @DisplayName("无服务时返回空列表")
        void shouldReturnEmptyList() {
            when(playgroundService.getServices("ling1")).thenReturn(Collections.emptyList());

            ApiResponse<List<ServiceMetadataDTO>> response = controller.getServices("ling1");

            assertTrue(response.isSuccess());
            assertTrue(response.getData().isEmpty());
        }

        @Test
        @DisplayName("service 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            when(playgroundService.getServices("ling1"))
                    .thenThrow(new RuntimeException("not loaded"));

            ApiResponse<List<ServiceMetadataDTO>> response = controller.getServices("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("Failed to get service metadata"));
        }
    }

    @Nested
    @DisplayName("invokeService")
    class InvokeServiceTests {

        @Test
        @DisplayName("正常调用服务并返回结果")
        void shouldInvokeService() {
            InvokeResultDTO result = new InvokeResultDTO();
            when(playgroundService.invokeService(
                    anyString(), anyString(), anyString(), any(String[].class), any(Object[].class),
                    any(), any(), any(Boolean.class)))
                    .thenReturn(result);

            ServicePlaygroundController.InvokeRequest request = new ServicePlaygroundController.InvokeRequest();
            request.setFqsid("svc1");
            request.setMethodName("greet");
            request.setParameterTypes(new String[]{"String"});
            request.setArgs(new Object[]{"hello"});
            request.setVersion("1.0.0");
            request.setRoutingMode("SPECIFIED");
            request.setSimulation(false);

            ApiResponse<InvokeResultDTO> response = controller.invokeService("ling1", request);

            assertTrue(response.isSuccess());
            assertSame(result, response.getData());
        }

        @Test
        @DisplayName("最小参数（仅 fqsid）也应正常调用")
        void shouldInvokeWithMinimalParams() {
            InvokeResultDTO result = new InvokeResultDTO();
            when(playgroundService.invokeService(
                    nullable(String.class), nullable(String.class), nullable(String.class),
                    any(), any(), nullable(String.class), nullable(String.class), any(Boolean.class)))
                    .thenReturn(result);

            ServicePlaygroundController.InvokeRequest request = new ServicePlaygroundController.InvokeRequest();
            request.setFqsid("svc1");

            ApiResponse<InvokeResultDTO> response = controller.invokeService("ling1", request);

            assertTrue(response.isSuccess());
            assertSame(result, response.getData());
        }

        @Test
        @DisplayName("service 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            when(playgroundService.invokeService(
                    nullable(String.class), nullable(String.class), nullable(String.class),
                    any(), any(), nullable(String.class), nullable(String.class), any(Boolean.class)))
                    .thenThrow(new RuntimeException("invoke failed"));

            ServicePlaygroundController.InvokeRequest request = new ServicePlaygroundController.InvokeRequest();
            request.setFqsid("svc1");

            ApiResponse<InvokeResultDTO> response = controller.invokeService("ling1", request);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("Service invocation failed"));
        }
    }
}
