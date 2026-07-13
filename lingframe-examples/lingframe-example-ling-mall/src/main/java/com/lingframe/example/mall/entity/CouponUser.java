package com.lingframe.example.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("t_coupon_user")
public class CouponUser implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long couponId;
    private Long userId;
    private Integer status; // 0-未使用, 1-已使用, 2-已过期
    private Long orderId;
    private Date receiveTime;
    private Date useTime;
}
