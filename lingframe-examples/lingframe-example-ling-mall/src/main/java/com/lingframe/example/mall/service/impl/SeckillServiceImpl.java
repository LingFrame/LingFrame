package com.lingframe.example.mall.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.dto.OrderCreateRequest;
import com.lingframe.example.mall.dto.OrderItemRequest;
import com.lingframe.example.mall.entity.Order;
import com.lingframe.example.mall.entity.SeckillActive;
import com.lingframe.example.mall.mapper.SeckillActiveMapper;
import com.lingframe.example.mall.service.OrderService;
import com.lingframe.example.mall.service.SeckillService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class SeckillServiceImpl extends ServiceImpl<SeckillActiveMapper, SeckillActive> implements SeckillService {

    private final SeckillActiveMapper seckillActiveMapper;
    private final OrderService orderService;

    // 内存预存秒杀库存 Key=activeId, Value=stock
    private static final Map<Long, Integer> ACTIVE_STOCK_CACHE = new ConcurrentHashMap<>();
    
    // 削峰阻塞队列
    private static final BlockingQueue<SeckillTask> SECKILL_QUEUE = new ArrayBlockingQueue<>(1000);
    
    // 凭证结果集: Key=voucher, Value=OrderId (-1L 代表下单失败)
    private static final Map<String, Long> VOUCHER_RESULTS = new ConcurrentHashMap<>();

    private static final ExecutorService CONSUMER_POOL = Executors.newSingleThreadExecutor();

    @Data
    @AllArgsConstructor
    private static class SeckillTask {
        private Long userId;
        private Long activeId;
        private Long skuId;
        private String voucher;
    }

    @PostConstruct
    public void init() {
        // 启动后台单线程异步消费者
        // ⚠️ 风险：CONSUMER_POOL 是 static 单线程池 + while(true) 阻塞出队，灵元热卸载时
        // 该线程不会被自动回收，构成 ClassLoader 泄漏链（线程持有灵元 ClassLoader 引用）。
        // 生产场景应在 Ling.onStop() 里 shutdown() 该线程池，示例为简化未做卸载钩子。
        CONSUMER_POOL.submit(() -> {
            while (true) {
                try {
                    SeckillTask task = SECKILL_QUEUE.take();
                    processSeckillTask(task);
                } catch (InterruptedException e) {
                    log.error("Seckill consumer thread interrupted", e);
                    break;
                } catch (Exception e) {
                    log.error("Failed to process seckill task", e);
                }
            }
        });
    }

    private void processSeckillTask(SeckillTask task) {
        log.info("Processing seckill task from queue: user={}, active={}", task.getUserId(), task.getActiveId());
        try {
            // 构造下单参数
            OrderCreateRequest request = new OrderCreateRequest();
            request.setReceiverName("秒杀自提客");
            request.setReceiverPhone("13900009999");
            request.setReceiverAddress("商城秒杀自提仓");
            
            OrderItemRequest item = new OrderItemRequest();
            item.setProductId(task.getSkuId()); // 传入 skuId
            item.setQuantity(1);
            request.setItems(Collections.singletonList(item));

            // 执行真实扣库存与写订单事务
            Order order = orderService.createOrder(task.getUserId(), request);
            if (order != null) {
                VOUCHER_RESULTS.put(task.getVoucher(), order.getId());
                log.info("Seckill order created successfully. voucher: {}, orderId: {}", task.getVoucher(), order.getId());
            } else {
                rollbackCacheStock(task.getActiveId());
                VOUCHER_RESULTS.put(task.getVoucher(), -1L);
            }
        } catch (Exception e) {
            log.error("Async seckill checkout failed: {}", e.getMessage());
            rollbackCacheStock(task.getActiveId());
            VOUCHER_RESULTS.put(task.getVoucher(), -1L);
        }
    }

    private void rollbackCacheStock(Long activeId) {
        ACTIVE_STOCK_CACHE.computeIfPresent(activeId, (k, v) -> v + 1);
    }

    @Override
    public String seckill(Long userId, Long activeId) {
        SeckillActive active = seckillActiveMapper.selectById(activeId);
        if (active == null) {
            throw new IllegalArgumentException("秒杀活动不存在");
        }
        Date now = new Date();
        if (now.before(active.getStartTime()) || now.after(active.getEndTime())) {
            throw new IllegalArgumentException("活动未开始或已结束");
        }

        // 1. 缓存预热加载 (首次加载)
        ACTIVE_STOCK_CACHE.putIfAbsent(activeId, active.getStock());

        // 2. 内存原子预减库存 (防止并发瞬间穿透数据库)
        synchronized (ACTIVE_STOCK_CACHE) {
            Integer stock = ACTIVE_STOCK_CACHE.get(activeId);
            if (stock <= 0) {
                throw new IllegalArgumentException("秒杀商品已被抢光啦");
            }
            ACTIVE_STOCK_CACHE.put(activeId, stock - 1);
        }

        // 3. 生成排队凭证，塞入削峰队列
        String voucher = "SECKILL_VOUCHER_" + IdUtil.fastSimpleUUID();
        SeckillTask task = new SeckillTask(userId, activeId, active.getSkuId(), voucher);
        
        boolean offered = SECKILL_QUEUE.offer(task);
        if (!offered) {
            // 队列溢出，高并发保护回滚
            rollbackCacheStock(activeId);
            throw new IllegalArgumentException("抢购人数太多，系统繁忙，请稍后再试");
        }

        return voucher;
    }

    @Override
    public Long querySeckillStatus(Long userId, String voucher) {
        // 返回订单ID (-1代表失败, null代表排队中)
        return VOUCHER_RESULTS.get(voucher);
    }
}
