package com.lingframe.example.saas.api.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class SaasOrder {
    private Long id;
    private String orderSn;
    private Long userId;
    private BigDecimal totalAmount;
}
