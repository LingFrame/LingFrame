package com.lingframe.example.saas.api.dto;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class SaasRefundInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private BigDecimal amount;
    private Integer status;
}
