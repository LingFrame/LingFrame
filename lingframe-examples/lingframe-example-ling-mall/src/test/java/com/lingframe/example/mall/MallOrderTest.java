package com.lingframe.example.mall;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingframe.example.mall.dto.OrderCreateRequest;
import com.lingframe.example.mall.dto.OrderItemRequest;
import com.lingframe.example.mall.entity.Inventory;
import com.lingframe.example.mall.entity.Order;
import com.lingframe.example.mall.service.InventoryService;
import com.lingframe.example.mall.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@Slf4j
public class MallOrderTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Test
    @DisplayName("高并发抢购防超卖并发测试")
    public void testConcurrentOrder() throws InterruptedException {
        // 使用 SKU 1 进行测试 (初始库存 100)
        Long skuId = 1L;
        int threadCount = 50;
        int orderAttempts = 120; // 抢购次数大于库存数
        
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(orderAttempts);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 构造抢购请求 (购买 1 件 SKU 1)
        OrderCreateRequest request = new OrderCreateRequest();
        request.setReceiverName("高并发测试客");
        request.setReceiverPhone("13800000000");
        request.setReceiverAddress("测试并发虚拟仓库");

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(skuId);
        item.setQuantity(1);
        request.setItems(Collections.singletonList(item));

        log.info("Starting concurrent checkout test: stock=100, attempts=120");

        for (int i = 0; i < orderAttempts; i++) {
            executorService.submit(() -> {
                try {
                    // testuser (userId=2) 模拟下单
                    Order order = orderService.createOrder(2L, request);
                    if (order != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // 检查库存档案
        Inventory inventory = inventoryService.getOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getSkuId, skuId));

        log.info("Concurrent test finished. Success orders: {}, Failed attempts: {}, Available Stock: {}, Locked Stock: {}",
                successCount.get(), failCount.get(), inventory.getStock(), inventory.getLockStock());

        // 断言:
        // 1. 可用库存不能为负数
        Assertions.assertTrue(inventory.getStock() >= 0, "库存超卖！可用库存为负数: " + inventory.getStock());
        // 2. 扣减成功数 + 剩余可用库存 + 剩余锁定库存 必须等于初始值 100
        int total = successCount.get() + inventory.getStock(); 
        // 因为 lockStock 是把 stock 转成了 lock_stock，所以订单生成后 lock_stock 会增加，可用 stock 会减少。
        // 由于 120 次尝试抢 100 个库存，所以成功的订单数必然是 100 个，剩余 stock 应该是 0，剩余 lock_stock 是 100。
        // 所以 successCount.get() 应为 100，剩余可用 stock 应该是 0。
        Assertions.assertEquals(100, total, "库存总量不守恒！");
        Assertions.assertEquals(100, successCount.get(), "成功的抢购数应该刚好是 100");
        Assertions.assertEquals(0, inventory.getStock(), "抢光后可用库存应该为 0");
        // 3. 锁定库存断言：100 次成功抢购每次扣减 1 转入 lockStock，抢光后 lockStock 应为 100，与 stock=0 守恒
        Assertions.assertEquals(100, inventory.getLockStock(), "抢光后锁定库存应为 100，与可用库存 0 守恒");
    }
}
