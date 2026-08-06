package com.lingframe.example.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingframe.example.mall.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
