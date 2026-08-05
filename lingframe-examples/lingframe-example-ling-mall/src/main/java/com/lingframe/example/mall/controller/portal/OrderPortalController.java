package com.lingframe.example.mall.controller.portal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingframe.example.mall.dto.OrderCreateRequest;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.entity.Logistics;
import com.lingframe.example.mall.entity.Order;
import com.lingframe.example.mall.entity.OrderItem;
import com.lingframe.example.mall.security.SecurityUtils;
import com.lingframe.example.mall.service.OrderItemService;
import com.lingframe.example.mall.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "4. 前台订单与售后接口", description = "提供创建订单、订单支付、物流跟踪及发起退款售后操作")
@RestController
@RequestMapping("/api/portal/orders")
@RequiredArgsConstructor
public class OrderPortalController {

    private final OrderService orderService;
    private final OrderItemService orderItemService;

    @Operation(summary = "创建订单", description = "提交订单，根据用户当前会员折扣和选择的优惠券自动计算折后价，锁定可用库存")
    @PostMapping("/create")
    public ResponseResult<Order> create(@Validated @RequestBody OrderCreateRequest request) {
        Long userId = SecurityUtils.getUserId();
        Order order = orderService.createOrder(userId, request);
        return ResponseResult.success(order);
    }

    @Operation(summary = "取消订单", description = "在15分钟内，用户可手动取消待付款的订单，并自动释放锁定的商品库存和退回优惠券")
    @PostMapping("/cancel/{orderId}")
    public ResponseResult<Void> cancel(@PathVariable Long orderId) {
        Long userId = SecurityUtils.getUserId();
        orderService.cancelOrder(orderId, userId);
        return ResponseResult.success();
    }

    @Operation(summary = "模拟支付", description = "模拟拉起支付，在后台生成MD5签名验签，并通过Webhook发起回调")
    @PostMapping("/pay")
    public ResponseResult<Void> pay(@RequestParam String orderSn) {
        orderService.payOrder(orderSn);
        return ResponseResult.success();
    }

    @Operation(summary = "三方支付异步回调Webhook (免Security拦截)", description = "接受外部支付网关请求，执行MD5验签与幂等性判重，执行付款状态变更、赠送积分及站内信")
    @PostMapping("/pay-callback")
    public ResponseResult<Void> payCallback(
            @RequestParam String orderSn,
            @RequestParam String paySn,
            @RequestParam String sign) {
        // 注: 此端点已在 WebSecurityConfig 放行，支持外部系统免Token直接请求
        orderService.handlePayCallback(orderSn, paySn, sign);
        return ResponseResult.success();
    }

    @Operation(summary = "获取订单明细", description = "查询订单详情及其全部商品快照项")
    @GetMapping("/detail/{orderId}")
    public ResponseResult<Map<String, Object>> getDetail(@PathVariable Long orderId) {
        Long userId = SecurityUtils.getUserId();
        Order order = orderService.getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("订单不存在");
        }
        List<OrderItem> items = orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        
        Map<String, Object> result = new HashMap<>();
        result.put("order", order);
        result.put("items", items);
        return ResponseResult.success(result);
    }

    @Operation(summary = "物流动态查询", description = "查询指定订单的运单配送状态及详细物流轨迹数据")
    @GetMapping("/logistics/{orderId}")
    public ResponseResult<Logistics> getLogistics(@PathVariable Long orderId) {
        Logistics logistics = orderService.getLogistics(orderId);
        return ResponseResult.success(logistics);
    }

    @Operation(summary = "发起售后退款", description = "对已支付待发货/待收货/已签收完成的订单申请退款，将冻结订单并录入退款审核流水")
    @PostMapping("/refund/apply")
    public ResponseResult<Void> applyRefund(
            @RequestParam Long orderId,
            @RequestParam String reason,
            @RequestParam BigDecimal amount) {
        Long userId = SecurityUtils.getUserId();
        orderService.applyRefund(orderId, userId, reason, amount);
        return ResponseResult.success();
    }
}
