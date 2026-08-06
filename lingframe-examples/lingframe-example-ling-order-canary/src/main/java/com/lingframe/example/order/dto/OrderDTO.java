package com.lingframe.example.order.dto;

import lombok.Data;

@Data
public class OrderDTO {
    private Long orderId;
    private String userName;
    /**
     * 金丝雀版本标记，仅金丝雀灵元实例返回时为 true
     */
    private Boolean canary;
}
