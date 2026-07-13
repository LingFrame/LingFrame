package com.lingframe.example.saas.api.dto;

import lombok.Data;

@Data
public class SaasOrderItemReq {
    private Long productId;
    private Integer quantity;
}
