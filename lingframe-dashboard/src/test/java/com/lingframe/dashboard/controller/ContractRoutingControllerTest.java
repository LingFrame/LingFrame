package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.ContractRoutingDTO;
import com.lingframe.dashboard.dto.ContractStressStepDTO;
import com.lingframe.dashboard.service.ContractRoutingService;
import com.lingframe.dashboard.service.SimulateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ContractRoutingController 单元测试")
class ContractRoutingControllerTest {

    private ContractRoutingService service;
    private SimulateService simulateService;
    private ContractRoutingController controller;

    @BeforeEach
    void setUp() {
        service = mock(ContractRoutingService.class);
        simulateService = mock(SimulateService.class);
        controller = new ContractRoutingController(service, simulateService);
    }

    @Test
    @DisplayName("列出多 Provider 契约")
    void listMultiProviderContracts() {
        when(service.listMultiProviderContracts()).thenReturn(Arrays.asList("svc-a", "svc-b"));

        ApiResponse<List<String>> resp = controller.listMultiProviderContracts();
        assertTrue(resp.isSuccess());
        assertEquals(2, resp.getData().size());
    }

    @Test
    @DisplayName("查询契约路由配置")
    void getContractRouting() {
        ContractRoutingDTO dto = ContractRoutingDTO.builder().contractId("svc-a").build();
        when(service.getContractRouting("svc-a")).thenReturn(dto);

        ApiResponse<ContractRoutingDTO> resp = controller.getContractRouting("svc-a");
        assertTrue(resp.isSuccess());
        assertEquals("svc-a", resp.getData().getContractId());
    }

    @Test
    @DisplayName("设置权重")
    void setProviderWeight() {
        ContractRoutingDTO dto = ContractRoutingDTO.builder().contractId("svc-a").build();
        when(service.getContractRouting("svc-a")).thenReturn(dto);

        Map<String, Object> body = new HashMap<>();
        body.put("providerKey", "user-ling:1.0.0");
        body.put("weight", 60);

        ApiResponse<ContractRoutingDTO> resp = controller.setProviderWeight("svc-a", body);
        assertTrue(resp.isSuccess());
        verify(service).setProviderWeight("svc-a", "user-ling:1.0.0", 60);
    }

    @Test
    @DisplayName("一键回滚到灵核")
    void rollbackToCore() {
        ContractRoutingDTO dto = ContractRoutingDTO.builder().contractId("svc-a").build();
        when(service.getContractRouting("svc-a")).thenReturn(dto);

        ApiResponse<ContractRoutingDTO> resp = controller.rollbackToCore("svc-a");
        assertTrue(resp.isSuccess());
        verify(service).rollbackToCore("svc-a");
    }

    @Test
    @DisplayName("契约流量演练单步")
    void stressContractStep() {
        ContractStressStepDTO result = ContractStressStepDTO.builder()
                .contractId("svc-a")
                .hitProviderKey("user-ling:1.0.0")
                .type("LING")
                .mode("PENETRATION")
                .durationMs(1.5)
                .build();
        when(simulateService.stressContractStep("svc-a", "PENETRATION")).thenReturn(result);

        ApiResponse<ContractStressStepDTO> resp = controller.stressContractStep("svc-a", "PENETRATION");
        assertTrue(resp.isSuccess());
        assertEquals("svc-a", resp.getData().getContractId());
        assertEquals("user-ling:1.0.0", resp.getData().getHitProviderKey());
        assertEquals("PENETRATION", resp.getData().getMode());
    }
}
