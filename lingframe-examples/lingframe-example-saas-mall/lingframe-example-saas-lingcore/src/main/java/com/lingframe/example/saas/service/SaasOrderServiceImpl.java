package com.lingframe.example.saas.service;

import com.lingframe.example.mall.dto.OrderCreateRequest;
import com.lingframe.example.mall.dto.OrderItemRequest;
import com.lingframe.example.mall.entity.Order;
import com.lingframe.example.mall.service.OrderService;
import com.lingframe.example.saas.api.SaasOrderService;
import com.lingframe.example.saas.api.dto.SaasOrder;
import com.lingframe.example.saas.api.dto.SaasOrderCreateReq;
import com.lingframe.example.saas.api.dto.SaasOrderItemReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SaasOrderServiceImpl implements SaasOrderService {

    private final OrderService orderService;

    @Override
    public SaasOrder createOrder(String tenantId, Long userId, SaasOrderCreateReq req) {
        log.info("SaaS Order Creation request received. Tenant: {}, User: {}", tenantId, userId);
        
        // 1. 转换显性契约 DTO 到底座内部业务 DTO
        OrderCreateRequest mallReq = new OrderCreateRequest();
        mallReq.setReceiverName(req.getReceiverName());
        mallReq.setReceiverPhone(req.getReceiverPhone());
        mallReq.setReceiverAddress(req.getReceiverAddress());
        
        List<OrderItemRequest> mallItems = new ArrayList<>();
        for (SaasOrderItemReq itemReq : req.getItems()) {
            OrderItemRequest mallItem = new OrderItemRequest();
            mallItem.setProductId(itemReq.getProductId());
            mallItem.setQuantity(itemReq.getQuantity());
            mallItems.add(mallItem);
        }
        mallReq.setItems(mallItems);

        // 2. 调用底座的磐石核心业务流程
        Order order = orderService.createOrder(userId, mallReq);
        if (order == null) {
            return null;
        }

        // 3. 返回契约数据结构
        SaasOrder saasOrder = new SaasOrder();
        saasOrder.setId(order.getId());
        saasOrder.setOrderSn(order.getOrderSn());
        saasOrder.setUserId(order.getUserId());
        saasOrder.setTotalAmount(order.getTotalAmount());
        return saasOrder;
    }

    @Override
    public void auditRefund(String tenantId, Long refundId, Integer status, String rejectReason, String operator) {
        log.info("SaaS Refund Approval callback triggered. Tenant: {}, TicketId: {}, Operator: {}", 
                tenantId, refundId, operator);
        // 执行核心底座审批逻辑
        orderService.auditRefund(refundId, status, rejectReason, operator);
    }
}
