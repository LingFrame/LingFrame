package com.lingframe.dashboard.service;

import com.lingframe.core.metrics.ProviderMetricsCollector;
import com.lingframe.dashboard.dto.ContractRoutingDTO;
import com.lingframe.dashboard.dto.ContractRoutingEvidenceDTO;
import com.lingframe.dashboard.dto.ProviderRoutingFactDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("契约路由观测服务测试")
class ContractRoutingEvidenceServiceTest {

    @Test
    @DisplayName("应返回有效策略和带实例代次的真实命中事实")
    void shouldReturnEffectivePolicyAndRuntimeFacts() {
        ContractRoutingService routingService = mock(ContractRoutingService.class);
        when(routingService.getContractRouting("order"))
                .thenReturn(ContractRoutingDTO.builder().contractId("order").policyRevision("8").build());
        ProviderMetricsCollector metrics = new ProviderMetricsCollector();
        metrics.recordInvocation("order", "order-ling", "1.1.0", "instance-2", "8", "weights", true, 12);
        metrics.recordInvocation("order", "order-ling", "1.1.0", "instance-2", "8", "weights", false, 18);
        metrics.recordInvocation("order", "lingcore-app", null, null, "8", "weights", true, 4);

        ContractRoutingEvidenceDTO evidence = new ContractRoutingEvidenceService(routingService, metrics)
                .getEvidence("order");

        assertEquals("8", evidence.getEffectivePolicy().getPolicyRevision());
        assertEquals(3, evidence.getTotalInvocations());
        assertEquals(2, evidence.getRoutingFacts().size());
        ProviderRoutingFactDTO fact = evidence.getRoutingFacts().stream()
                .filter(item -> "instance-2".equals(item.getInstanceId()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("instance-2", fact.getInstanceId());
        assertEquals("8", fact.getPolicyRevision());
        assertEquals("weights", fact.getRoutingReason());
        assertEquals(200.0 / 3.0, fact.getActualPercent(), 0.0001);
        assertEquals(15.0, fact.getAvgDurationMs(), 0.0001);
    }

    @Test
    @DisplayName("没有运行时命中时应返回空事实列表")
    void shouldReturnEmptyFactsWhenNoInvocationRecorded() {
        ContractRoutingService routingService = mock(ContractRoutingService.class);
        ContractRoutingDTO policy = ContractRoutingDTO.builder().contractId("order").build();
        when(routingService.getContractRouting("order")).thenReturn(policy);

        ContractRoutingEvidenceDTO evidence = new ContractRoutingEvidenceService(
                routingService, new ProviderMetricsCollector()).getEvidence("order");

        assertNotNull(evidence.getRoutingFacts());
        assertEquals(Collections.emptyList(), evidence.getRoutingFacts());
        assertEquals(0, evidence.getTotalInvocations());
    }
}
