package com.lingframe.example.mall.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.List;

@Data
public class OrderCreateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "收货人不能为空")
    private String receiverName;

    @NotBlank(message = "收货人电话不能为空")
    private String receiverPhone;

    @NotBlank(message = "收货地址不能为空")
    private String receiverAddress;

    private Long couponUserId; // 用户优惠券记录的ID, 可为空

    @NotEmpty(message = "订单项不能为空")
    @Valid
    private List<OrderItemRequest> items;
}
