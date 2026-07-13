package com.lingframe.example.saas.api;

import com.lingframe.example.saas.api.dto.SaasRefundTicket;

public interface RefundPolicy {

    boolean evaluateAndApply(String tenantId, SaasRefundTicket ticket, Integer status, String rejectReason, String operator);
}
