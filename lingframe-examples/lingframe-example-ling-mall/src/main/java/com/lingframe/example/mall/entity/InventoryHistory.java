package com.lingframe.example.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("t_inventory_history")
public class InventoryHistory implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skuId;
    private Integer changeNum;
    private Integer type; // 0-下单锁库存, 1-管理员手动调整, 2-支付发货扣减, 3-超时释放, 4-售后回滚
    private String operator;
    private String remark;
    private Date createdAt;
}
