package com.lingframe.example.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("t_seckill_active")
public class SeckillActive implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spuId;
    private Long skuId;
    private BigDecimal seckillPrice;
    private Integer stock;
    private Date startTime;
    private Date endTime;
}
