package com.lingframe.example.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("t_order")
public class Order implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderSn;
    private Long userId;
    private BigDecimal totalAmount;
    private Integer status; // 0-待付款, 1-待发货, 2-待收货, 3-已完成, 4-已取消, 5-退款中, 6-已退款, 7-拒绝退款
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private Date createdAt;
    private Date paidAt;
    private Date canceledAt;
}
