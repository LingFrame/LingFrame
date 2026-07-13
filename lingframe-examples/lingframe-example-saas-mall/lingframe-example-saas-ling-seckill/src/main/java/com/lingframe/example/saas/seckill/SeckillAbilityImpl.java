package com.lingframe.example.saas.seckill;

import cn.hutool.core.util.IdUtil;
import com.lingframe.api.annotation.LingReference;
import com.lingframe.example.saas.api.SeckillAbility;
import com.lingframe.example.saas.api.SaasOrderService;
import com.lingframe.example.saas.api.SaasSeckillActiveQueryService;
import com.lingframe.example.saas.api.dto.SaasOrder;
import com.lingframe.example.saas.api.dto.SaasOrderCreateReq;
import com.lingframe.example.saas.api.dto.SaasOrderItemReq;
import com.lingframe.example.saas.api.dto.SaasSeckillActive;
import com.lingframe.example.saas.api.dto.SeckillResult;
import com.lingframe.example.saas.api.dto.SeckillStatusResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * SaaS 商城秒杀削峰灵元能力实现。
 * <p>
 * 通过 {@code @Component} 注册到灵元 Spring 子容器，灵核侧以 {@code @LingReference SeckillAbility} 注入跨类加载器代理。
 */
@Slf4j
@Component
public class SeckillAbilityImpl implements SeckillAbility {

    private static final Map<Long, Integer> ACTIVE_STOCK_CACHE = new ConcurrentHashMap<>();
    private static final BlockingQueue<SeckillTask> SECKILL_QUEUE = new ArrayBlockingQueue<>(1000);
    private static final Map<String, Long> VOUCHER_RESULTS = new ConcurrentHashMap<>();
    private static final ExecutorService CONSUMER_POOL = Executors.newSingleThreadExecutor();

    // 显性契约注入：灵元→灵核反向调用，通过 @LingReference 路由到灵核 SaasOrderService。
    @LingReference
    private SaasOrderService saasOrderService;

    @LingReference
    private SaasSeckillActiveQueryService activeQueryService;

    @Data
    @AllArgsConstructor
    private static class SeckillTask {
        private String tenantId;
        private Long userId;
        private Long activeId;
        private Long skuId;
        private String voucher;
    }

    public SeckillAbilityImpl() {
        // 启动异步出队消费者线程
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
        log.info("Processing seckill (Tenant: {}): user={}, active={}", task.getTenantId(), task.getUserId(), task.getActiveId());
        try {
            // 直接进行强类型显式方法调用，100% 类型安全且消除一切反射！
            SaasOrderCreateReq req = new SaasOrderCreateReq();
            req.setReceiverName("SaaS秒杀自提");
            req.setReceiverPhone("13900008888");
            req.setReceiverAddress("SaaS秒杀配货仓");
            
            SaasOrderItemReq item = new SaasOrderItemReq();
            item.setProductId(task.getSkuId());
            item.setQuantity(1);
            
            List<SaasOrderItemReq> items = new ArrayList<>();
            items.add(item);
            req.setItems(items);

            SaasOrder saasOrder = saasOrderService.createOrder(task.getTenantId(), task.getUserId(), req);

            if (saasOrder != null && saasOrder.getId() != null) {
                VOUCHER_RESULTS.put(task.getVoucher(), saasOrder.getId());
                log.info("Seckill checkout success. tenant: {}, voucher: {}, orderId: {}", 
                        task.getTenantId(), task.getVoucher(), saasOrder.getId());
            } else {
                rollbackStock(task.getActiveId());
                VOUCHER_RESULTS.put(task.getVoucher(), -1L);
            }
        } catch (Exception e) {
            log.error("Failed to execute async checkout seckill order", e);
            rollbackStock(task.getActiveId());
            VOUCHER_RESULTS.put(task.getVoucher(), -1L);
        }
    }

    private void rollbackStock(Long activeId) {
        ACTIVE_STOCK_CACHE.computeIfPresent(activeId, (k, v) -> v + 1);
    }

    @Override
    public SeckillResult seckill(String tenantId, Long userId, Long activeId) {
        // 1. 通过 @LingReference 接口查询活动明细，0个 JDBC！0个 MyBatis-Plus 直接依赖！
        SaasSeckillActive active = activeQueryService.getActiveById(activeId);
        if (active == null) {
            throw new IllegalArgumentException("秒杀活动不存在");
        }

        Date now = new Date();
        if (now.before(active.getStartTime()) || now.after(active.getEndTime())) {
            throw new IllegalArgumentException("活动未开始或已结束");
        }

        Long skuId = active.getSkuId();
        int stockLimit = active.getStock();

        // 2. 本地缓存预减
        ACTIVE_STOCK_CACHE.putIfAbsent(activeId, stockLimit);
        synchronized (ACTIVE_STOCK_CACHE) {
            int currentStock = ACTIVE_STOCK_CACHE.get(activeId);
            if (currentStock <= 0) {
                throw new IllegalArgumentException("秒杀商品已被抢光啦");
            }
            ACTIVE_STOCK_CACHE.put(activeId, currentStock - 1);
        }

        // 3. 塞入秒杀队列
        String voucher = "SECKILL_" + tenantId + "_" + IdUtil.fastSimpleUUID();
        SeckillTask task = new SeckillTask(tenantId, userId, activeId, skuId, voucher);
        boolean success = SECKILL_QUEUE.offer(task);
        if (!success) {
            rollbackStock(activeId);
            throw new IllegalArgumentException("抢购人数过多，排队失败，请重试");
        }

        SeckillResult res = new SeckillResult();
        res.setVoucher(voucher);
        res.setStatus("QUEUEING");
        return res;
    }

    @Override
    public SeckillStatusResult queryStatus(String tenantId, Long userId, String voucher) {
        Long orderId = VOUCHER_RESULTS.get(voucher);
        SeckillStatusResult res = new SeckillStatusResult();
        res.setVoucher(voucher);
        if (orderId == null) {
            res.setStatus("QUEUEING");
            res.setOrderId(null);
        } else if (orderId == -1L) {
            res.setStatus("FAIL");
            res.setOrderId(null);
        } else {
            res.setStatus("SUCCESS");
            res.setOrderId(orderId);
        }
        return res;
    }
}
