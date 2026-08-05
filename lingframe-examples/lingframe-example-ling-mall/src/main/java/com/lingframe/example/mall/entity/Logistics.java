package com.lingframe.example.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("t_logistics")
public class Logistics implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String deliveryCompany;
    private String deliverySn;
    private Integer status; // 0-未发货, 1-运输中, 2-派送中, 3-已签收
    private String traceData;
}
