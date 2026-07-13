package com.lingframe.example.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.example.mall.entity.Inventory;

public interface InventoryService extends IService<Inventory> {

    boolean lockStock(Long skuId, Integer count);

    boolean deductLockedStock(Long skuId, Integer count);

    boolean releaseStock(Long skuId, Integer count);

    void adjustStock(Long skuId, Integer stock, String operator);
}
