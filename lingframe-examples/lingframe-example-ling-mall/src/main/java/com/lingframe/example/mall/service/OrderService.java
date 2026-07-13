package com.lingframe.example.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.example.mall.dto.OrderCreateRequest;
import com.lingframe.example.mall.entity.Logistics;
import com.lingframe.example.mall.entity.Order;

import java.math.BigDecimal;

public interface OrderService extends IService<Order> {

    Order createOrder(Long userId, OrderCreateRequest request);

    void cancelOrder(Long orderId, Long userId);

    void payOrder(String orderSn);

    /**
     * Webhook 支付成功异步回调处理器 (包含安全验签与幂等性校验)
     */
    void handlePayCallback(String orderSn, String paySn, String sign);

    void shipOrder(Long orderId, String deliveryCompany, String deliverySn);

    /**
     * 用户申请售后退款
     */
    void applyRefund(Long orderId, Long userId, String reason, BigDecimal amount);

    /**
     * 管理员审核售后退款 (退款成功将释放库存、退还优惠券并扣减对应会员积分与成长值)
     */
    void auditRefund(Long refundId, Integer status, String rejectReason, String operator);

    Logistics getLogistics(Long orderId);
}
