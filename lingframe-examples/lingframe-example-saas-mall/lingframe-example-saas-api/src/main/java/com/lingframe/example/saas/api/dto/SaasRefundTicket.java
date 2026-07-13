package com.lingframe.example.saas.api.dto;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class SaasRefundTicket implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long orderId;
    private Long userId;
    private BigDecimal amount;
    private Integer status;
    private String reason;
}
