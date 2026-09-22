package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 契约的生效策略与实际命中事实。
 * <p>
 * 策略快照和调用指标分别读取，不能宣称跨两者的事务一致性；每条事实自带记录时的策略修订号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRoutingEvidenceDTO {

    private ContractRoutingDTO effectivePolicy;
    private long totalInvocations;
    private List<ProviderRoutingFactDTO> routingFacts;
}
