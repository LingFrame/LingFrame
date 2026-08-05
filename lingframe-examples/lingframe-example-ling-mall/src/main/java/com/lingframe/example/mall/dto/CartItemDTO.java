package com.lingframe.example.mall.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class CartItemDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long productId; // 挂载的 SKU ID
    private String productName; // 商品规格名称
    private BigDecimal price; // 商品单价
    private Integer quantity; // 数量
    private String imageUrl; // 主图
}
