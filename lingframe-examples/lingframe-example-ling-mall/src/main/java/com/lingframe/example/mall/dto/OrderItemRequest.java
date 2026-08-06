package com.lingframe.example.mall.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

@Data
public class OrderItemRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "产品ID不能为空")
    private Long productId; // 挂载的 SKU ID

    @NotNull(message = "商品数量不能为空")
    @Min(value = 1, message = "订购数量最少为1")
    private Integer quantity;
}
