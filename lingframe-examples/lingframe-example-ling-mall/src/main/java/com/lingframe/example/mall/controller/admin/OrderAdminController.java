package com.lingframe.example.mall.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.entity.OrderRefund;
import com.lingframe.example.mall.security.SecurityUtils;
import com.lingframe.example.mall.service.OrderRefundService;
import com.lingframe.example.mall.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "8. 后台订单与售后管理 (Admin)", description = "供商户管理员进行订单发货履约及售后单同意/拒绝审核")
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderService orderService;
    private final OrderRefundService orderRefundService;

    @Operation(summary = "订单发货", description = "管理员录入物流公司和运单号进行履约发货，扣除锁定库存并追加物流轨迹")
    @PostMapping("/ship/{orderId}")
    @PreAuthorize("hasAuthority('order:admin:ship')")
    public ResponseResult<Void> ship(
            @PathVariable Long orderId,
            @RequestParam String deliveryCompany,
            @RequestParam String deliverySn) {
        orderService.shipOrder(orderId, deliveryCompany, deliverySn);
        return ResponseResult.success();
    }

    @Operation(summary = "售后退款单列表", description = "获取当前所有售后退款申请明细列表")
    @GetMapping("/refund/list")
    @PreAuthorize("hasAuthority('order:admin:refundAudit')")
    public ResponseResult<List<OrderRefund>> getRefundList(@RequestParam(required = false) Integer status) {
        List<OrderRefund> list = orderRefundService.list(new LambdaQueryWrapper<OrderRefund>()
                .eq(status != null, OrderRefund::getStatus, status)
                .orderByDesc(OrderRefund::getCreatedAt));
        return ResponseResult.success(list);
    }

    @Operation(summary = "审核售后退款", description = "审核退款单。若同意，将自动回退商品库存及优惠券，并扣除该订单赠送给用户的会员成长值与积分")
    @PostMapping("/refund/audit/{refundId}")
    @PreAuthorize("hasAuthority('order:admin:refundAudit')")
    public ResponseResult<Void> auditRefund(
            @PathVariable Long refundId,
            @RequestParam Integer status, // 1-同意退款, 2-拒绝退款
            @RequestParam(required = false) String rejectReason) {
        String username = SecurityUtils.getLoginUser().getUsername();
        orderService.auditRefund(refundId, status, rejectReason, username);
        return ResponseResult.success();
    }
}
