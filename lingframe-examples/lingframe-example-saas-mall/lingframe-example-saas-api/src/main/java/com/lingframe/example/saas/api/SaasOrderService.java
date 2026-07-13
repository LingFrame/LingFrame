package com.lingframe.example.saas.api;

import com.lingframe.example.saas.api.dto.SaasOrder;
import com.lingframe.example.saas.api.dto.SaasOrderCreateReq;

public interface SaasOrderService {

    SaasOrder createOrder(String tenantId, Long userId, SaasOrderCreateReq req);

    void auditRefund(String tenantId, Long refundId, Integer status, String rejectReason, String operator);
}
