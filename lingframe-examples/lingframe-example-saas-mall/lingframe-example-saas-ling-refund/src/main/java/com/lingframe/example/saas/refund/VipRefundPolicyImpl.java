package com.lingframe.example.saas.refund;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.example.saas.api.RefundPolicy;
import com.lingframe.example.saas.api.SaasOrderService;
import com.lingframe.example.saas.api.dto.SaasRefundTicket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SaaS 商城 VIP 极速退款决策灵元能力实现。
 * <p>
 * 通过 {@code @Component} 注册到灵元 Spring 子容器，灵核侧以 {@code @LingReference RefundPolicy} 注入跨类加载器代理。
 */
@Slf4j
@Component
public class VipRefundPolicyImpl implements RefundPolicy {

    // 显性契约注入：灵元→灵核反向调用，通过 @LingReference 路由到灵核 SaasOrderService。
    @LingReference
    private SaasOrderService saasOrderService;

    @Override
    public boolean evaluateAndApply(String tenantId, SaasRefundTicket ticket, Integer status, String rejectReason, String operator) {
        log.info("Evaluating VIP refund auto-policy for tenant: {}, refund ticket ID: {}", tenantId, ticket.getId());
        
        // 核心 SaaS 多租户分流判定
        if (!"tenant_vip".equals(tenantId)) {
            log.info("Tenant '{}' is not eligible for VIP auto-refund, bypassing.", tenantId);
            return false;
        }

        // 仅在申请中状态下自动同意
        if (ticket.getStatus() != 0) {
            log.info("Refund ticket {} status is {}, bypass VIP policy.", ticket.getId(), ticket.getStatus());
            return false;
        }

        // KA VIP 租户急速退款规则：10000.00元以内的订单全自动秒退
        if (ticket.getAmount().compareTo(new java.math.BigDecimal("10000.00")) < 0) {
            log.info("Refund amount {} is eligible. Executing VIP auto-approval on tenant: {} ...", ticket.getAmount(), tenantId);
            
            // 直接进行显式方法调用：零反射，强类型安全！
            saasOrderService.auditRefund(tenantId, ticket.getId(), 1, null, "SYSTEM-VIP-AUTO");
            log.info("VIP auto-approval succeeded for refund ticket ID: {}", ticket.getId());
            return true;
        }
        return false;
    }
}
