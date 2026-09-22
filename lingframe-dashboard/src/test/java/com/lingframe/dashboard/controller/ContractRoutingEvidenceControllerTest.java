package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.ContractRoutingEvidenceDTO;
import com.lingframe.dashboard.service.ContractRoutingEvidenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("契约路由观测控制器测试")
class ContractRoutingEvidenceControllerTest {

    @Test
    @DisplayName("应返回契约路由命中事实")
    void shouldReturnEvidence() {
        ContractRoutingEvidenceService service = mock(ContractRoutingEvidenceService.class);
        ContractRoutingEvidenceDTO evidence = ContractRoutingEvidenceDTO.builder()
                .totalInvocations(10).build();
        when(service.getEvidence("order")).thenReturn(evidence);

        ApiResponse<ContractRoutingEvidenceDTO> response =
                new ContractRoutingEvidenceController(service).getEvidence("order");

        assertTrue(response.isSuccess());
        assertSame(evidence, response.getData());
    }

    @Test
    @DisplayName("观测服务失败时应返回失败响应")
    void shouldReturnErrorWhenServiceFails() {
        ContractRoutingEvidenceService service = mock(ContractRoutingEvidenceService.class);
        when(service.getEvidence("order")).thenThrow(new IllegalStateException("not ready"));

        ApiResponse<ContractRoutingEvidenceDTO> response =
                new ContractRoutingEvidenceController(service).getEvidence("order");

        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().contains("命中事实失败"));
    }
}
