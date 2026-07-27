package com.lingframe.example.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.entity.Inventory;
import com.lingframe.example.mall.entity.InventoryHistory;
import com.lingframe.example.mall.mapper.InventoryHistoryMapper;
import com.lingframe.example.mall.mapper.InventoryMapper;
import com.lingframe.example.mall.service.InventoryService;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl extends ServiceImpl<InventoryMapper, Inventory> implements InventoryService {

    private final InventoryMapper inventoryMapper;
    private final InventoryHistoryMapper inventoryHistoryMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean lockStock(Long skuId, Integer count) {
        if (count <= 0) {
            throw new IllegalArgumentException("锁定的库存数量必须大于0");
        }
        int rows = inventoryMapper.lockStock(skuId, count);
        if (rows > 0) {
            // 写入库存流水日志 (type=0 下单锁库存)
            recordHistory(skuId, -count, 0, getCurrentOperator(), "下单锁定库存");
            return true;
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductLockedStock(Long skuId, Integer count) {
        if (count <= 0) {
            throw new IllegalArgumentException("扣减的库存数量必须大于0");
        }
        int rows = inventoryMapper.deductLockedStock(skuId, count);
        if (rows > 0) {
            // 写入库存流水日志 (type=2 支付发货扣减)
            recordHistory(skuId, -count, 2, getCurrentOperator(), "支付发货扣除锁定库存");
            return true;
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseStock(Long skuId, Integer count) {
        if (count <= 0) {
            throw new IllegalArgumentException("释放的库存数量必须大于0");
        }
        int rows = inventoryMapper.releaseStock(skuId, count);
        if (rows > 0) {
            // 写入库存流水日志 (type=3 超时释放)
            recordHistory(skuId, count, 3, getCurrentOperator(), "订单取消释放锁定库存");
            return true;
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustStock(Long skuId, Integer stock, String operator) {
        if (stock < 0) {
            throw new IllegalArgumentException("调整的可用库存数不能小于0");
        }
        Inventory inventory = inventoryMapper.selectOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));
        if (inventory == null) {
            throw new IllegalArgumentException("库存档案不存在");
        }

        int oldStock = inventory.getStock();
        int changeNum = stock - oldStock;
        
        inventory.setStock(stock);
        inventoryMapper.updateById(inventory);

        // 写入库存流水日志 (type=1 管理员手动调整)
        recordHistory(skuId, changeNum, 1, operator, "管理员手动修改可用库存: 从 " + oldStock + " 调整为 " + stock);
    }

    private void recordHistory(Long skuId, Integer changeNum, Integer type, String operator, String remark) {
        InventoryHistory history = new InventoryHistory();
        history.setSkuId(skuId);
        history.setChangeNum(changeNum);
        history.setType(type);
        history.setOperator(operator);
        history.setRemark(remark);
        history.setCreatedAt(new Date());
        inventoryHistoryMapper.insert(history);
    }

    private String getCurrentOperator() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception e) {
            // ignore
        }
        return "SYSTEM";
    }
}
