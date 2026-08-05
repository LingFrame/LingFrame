package com.lingframe.example.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingframe.example.mall.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    @Update("UPDATE t_inventory SET stock = stock - #{count}, lock_stock = lock_stock + #{count} WHERE sku_id = #{skuId} AND stock >= #{count}")
    int lockStock(Long skuId, Integer count);

    @Update("UPDATE t_inventory SET lock_stock = lock_stock - #{count} WHERE sku_id = #{skuId} AND lock_stock >= #{count}")
    int deductLockedStock(Long skuId, Integer count);

    @Update("UPDATE t_inventory SET stock = stock + #{count}, lock_stock = lock_stock - #{count} WHERE sku_id = #{skuId} AND lock_stock >= #{count}")
    int releaseStock(Long skuId, Integer count);
}
