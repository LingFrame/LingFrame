package com.lingframe.example.saas.controller;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.entity.OrderRefund;
import com.lingframe.example.mall.mapper.OrderRefundMapper;
import com.lingframe.example.mall.service.OrderService;
import com.lingframe.example.saas.api.RefundPolicy;
import com.lingframe.example.saas.api.dto.SaasRefundTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "SaaS-3. 售后退款决策代理 (SaaS 灵核)", description = "优先委派定制退款灵元做自动化急速决策")
@RestController
@RequestMapping("/api/saas/admin/orders")
@RequiredArgsConstructor
@Slf4j
public class SaasRefundController {

    private final OrderService orderService;

    // 引入底座原生的 MyBatis-Plus Mapper，保持原有技术栈高度统一！
    private final OrderRefundMapper orderRefundMapper;

    // 显性契约注入：使用 @LingReference 由灵珑底层自动织入动态路由治理代理，平滑跨越类加载器边界
    @LingReference
    private RefundPolicy refundPolicy;

    @Operation(summary = "审核售后退款", description = "优先让VIP退款灵元做自动化自动同意；若未满足或未装载，退回灵核核心人工审批流程")
    @PostMapping("/refund/audit/{refundId}")
    @PreAuthorize("hasAuthority('order:admin:refundAudit')")
    public ResponseResult<String> auditRefund(
            @RequestParam String tenantId,
            @PathVariable Long refundId,
            @RequestParam Integer status,
            @RequestParam(required = false) String rejectReason) {
        
        // 1. 使用 MyBatis-Plus 查出底座售后单并转换为契约 DTO 传递给灵元
        OrderRefund refund = orderRefundMapper.selectById(refundId);
        if (refund != null) {
            SaasRefundTicket ticket = new SaasRefundTicket();
            ticket.setId(refund.getId());
            ticket.setOrderId(refund.getOrderId());
            ticket.setUserId(refund.getUserId());
            ticket.setAmount(refund.getAmount());
            ticket.setStatus(refund.getStatus());
            ticket.setReason(refund.getReason());

            log.info("VIP refund policy ling is active, evaluating for tenant: {} ...", tenantId);
            boolean handled = refundPolicy.evaluateAndApply(tenantId, ticket, status, rejectReason, "SaaS_Auto");
            if (handled) {
                return ResponseResult.success("该退款已由【至尊VIP极速退款灵元】全自动拦截并处理同意！");
            }
        }

        // 2. 灵元未匹配到或未装载，执行底座默认人工流程
        log.info("Fall back to default manual approval process.");
        orderService.auditRefund(refundId, status, rejectReason, "MANUAL_ADMIN");
        return ResponseResult.success("退款审批成功 (已由人工管理员完成审批)");
    }
}
