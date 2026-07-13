package com.lingframe.dashboard.service;

import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.ProviderDescriptor;
import com.lingframe.core.ling.ProviderKind;
import com.lingframe.core.router.ProviderWeightRouter;
import com.lingframe.dashboard.dto.ContractRoutingDTO;
import com.lingframe.dashboard.dto.ProviderWeightDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 契约路由策略服务。
 * <p>
 * Dashboard 契约路由页面的后端业务层，负责：
 * <ol>
 *   <li>列出所有有多 provider 的契约</li>
 *   <li>查询某契约的 provider 列表 + 当前权重</li>
 *   <li>下发权重覆盖（通过 {@link ProviderWeightRouter}）</li>
 *   <li>一键回滚到灵核 100%（CORE=100, LING=0）</li>
 * </ol>
 * <p>
 * 权重来源语义：
 * <ul>
 *   <li>{@code registeredWeight}——注册时携带的初始权重，不可变</li>
 *   <li>{@code overrideWeight}——Dashboard 运行期下发，null 表示未覆盖</li>
 *   <li>{@code effectiveWeight}——overrideWeight 非空时取它，否则按 ADR 决策 6（CORE=100, LING=0）</li>
 * </ul>
 */
@Slf4j
public class ContractRoutingService {

    private final LingServiceRegistry lingServiceRegistry;
    private final ProviderWeightRouter providerWeightRouter;

    public ContractRoutingService(LingServiceRegistry lingServiceRegistry,
                                  ProviderWeightRouter providerWeightRouter) {
        this.lingServiceRegistry = lingServiceRegistry;
        this.providerWeightRouter = providerWeightRouter;
    }

    /**
     * 列出所有有多 provider 的契约 ID。
     *
     * @return 有 ≥2 个 provider 的契约 ID 列表；无任何注册时返回空列表
     */
    public List<String> listMultiProviderContracts() {
        Set<String> allContracts = lingServiceRegistry.getAllContractIds();
        return allContracts.stream()
                .filter(cid -> lingServiceRegistry.getProvidersByContractId(cid).size() >= 2)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 查询某契约的 provider 列表 + 权重配置。
     *
     * @param contractId 契约 ID
     * @return 路由策略 DTO；契约未注册时返回空 providers 列表
     */
    public ContractRoutingDTO getContractRouting(String contractId) {
        List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
        List<ProviderWeightDTO> providerDtos = new ArrayList<>(providers.size());

        int coreEffective = 0;
        int lingEffective = 0;

        for (ProviderDescriptor desc : providers) {
            Integer override = providerWeightRouter.getOverrideWeight(contractId, desc.getLingId());
            int effective = computeEffectiveWeight(desc, override);
            providerDtos.add(ProviderWeightDTO.builder()
                    .lingId(desc.getLingId())
                    .kind(desc.getKind())
                    .registeredWeight(desc.getWeight())
                    .overrideWeight(override)
                    .effectiveWeight(effective)
                    .build());

            if (desc.getKind() == ProviderKind.CORE) {
                coreEffective += effective;
            } else {
                lingEffective += effective;
            }
        }

        return ContractRoutingDTO.builder()
                .contractId(contractId)
                .providers(providerDtos)
                .multiProvider(providers.size() >= 2)
                .coreEffectiveWeight(coreEffective)
                .lingEffectiveWeight(lingEffective)
                .build();
    }

    /**
     * 设置某契约下指定 provider 的权重。
     * <p>
     * 权重值会被 clamp 到 [0, 100]。
     *
     * @param contractId 契约 ID
     * @param lingId     提供方灵元/灵核 ID
     * @param weight     新权重 0-100
     */
    public void setProviderWeight(String contractId, String lingId, int weight) {
        providerWeightRouter.setProviderWeight(contractId, lingId, weight);
        log.info("[ContractRouting] Weight updated: contract=[{}], ling=[{}], weight=[{}]",
                contractId, lingId, weight);
    }

    /**
     * 一键回滚到灵核 100%。
     * <p>
     * 将该契约下所有 CORE provider 权重设为 100，所有 LING provider 权重设为 0。
     * 这与清除覆盖（回退到 ADR 默认值）等价，但显式设置覆盖确保即使无 CORE provider
     * 也能保持一致的「灵核优先」语义。
     *
     * @param contractId 契约 ID
     */
    public void rollbackToCore(String contractId) {
        List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
        for (ProviderDescriptor desc : providers) {
            int targetWeight = (desc.getKind() == ProviderKind.CORE) ? 100 : 0;
            providerWeightRouter.setProviderWeight(contractId, desc.getLingId(), targetWeight);
        }
        log.info("[ContractRouting] Rollback to core 100%: contract=[{}], providers=[{}]",
                contractId, providers.size());
    }

    /**
     * 计算生效权重：override 非空时取 override，否则按 ADR 决策 6（CORE=100, LING=0）。
     */
    private int computeEffectiveWeight(ProviderDescriptor desc, Integer override) {
        if (override != null) {
            return Math.max(0, Math.min(100, override));
        }
        return (desc.getKind() == ProviderKind.CORE) ? 100 : 0;
    }
}
