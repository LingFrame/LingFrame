package com.lingframe.example.saas.inventory.service.impl;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.example.mall.service.InventoryService;
import com.lingframe.example.saas.inventory.service.InventoryHoldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 带 TTL 的库存预占服务灵元实现——新增灵核不存在的能力。
 * <p>
 * 设计要点（绞杀迁移示例之三：新增灵核不存在的功能）：
 * <ul>
 *   <li>灵核 {@code InventoryService} 只有 lockStock/releaseStock，无超时自动释放机制。
 *       灵元在灵核能力之上叠加 TTL：预占后若未在有效期内确认扣减，自动释放回可用库存。</li>
 *   <li>灵元自定义接口 {@link InventoryHoldService}，不 extends MyBatis-Plus IService，
 *       无 delegate 桩代码——这是"新增功能灵元"的优势，灵元不依赖灵核接口契约。</li>
 *   <li>灵元是唯一 provider（灵核无此契约），无双 provider 切流。
 *       灵元卸载后该能力消失，体现"新增能力"的边界。</li>
 *   <li>预占记录存内存（灵元无 DataSource），通过 {@code @LingReference} delegate 灵核操作真实库存。
 *       SmartServiceProxy 零强引用设计，灵元可热卸载。</li>
 *   <li><b>资源泄漏风险</b>：{@link #scheduler} 虽是 daemon 线程（JVM 退出时终止），但灵元卸载时
 *       JVM 不退出，daemon 线程不会自动终止。其调度任务通过 lambda 闭包持有本实例引用
 *       （{@code autoExpire} 是实例方法）→ 持有灵元 Class → 持有灵元 ClassLoader。
 *       灵元卸载时若不 shutdown 调度器，线程会长期持有这条引用链，阻止 ClassLoader 被 GC 回收。
 *       故实现 {@link DisposableBean#destroy()}，由 {@code SpringLingContainer.stop()} →
 *       {@code context.close()} 触发，shutdown 调度器 + 清空预占记录。</li>
 * </ul>
 */
@Slf4j
@Component
public class InventoryHoldServiceImpl implements InventoryHoldService, DisposableBean {

    /**
     * 显式 pinning 到灵核：库存扣减/释放由灵核 InventoryService 执行。
     */
    @LingReference(lingId = "lingcore-app")
    private InventoryService coreInventoryService;

    /** 预占单据内存存储（灵元无 DataSource） */
    private final Map<String, HoldRecord> holdRecords = new ConcurrentHashMap<>();

    /** 预占 TTL 上限：避免调度极远未来任务，灵元卸载时才能清理 */
    private static final long MAX_TTL_SECONDS = 3600L;

    /** 超时自动释放调度器 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "inventory-hold-expiry");
        t.setDaemon(true);
        return t;
    });

    /**
     * Spring 用的无参构造器：显式声明，避免被下方包私有有参构造器覆盖后默认无参构造器消失。
     * 创建后由 {@code LingReferenceInjector} BPP 注入 {@code @LingReference} 字段。
     */
    public InventoryHoldServiceImpl() {
    }

    /**
     * 测试用构造器：包私有，直接传入 mock 灵核代理，避免反射注入 private 字段。
     * <p>
     * 生产环境由无参构造 + BPP 注入；{@link com.lingframe.starter.processor.LingReferenceInjector}
     * 对非 null 字段跳过注入，故此构造器填入的字段不会被 BPP 覆盖。
     */
    InventoryHoldServiceImpl(InventoryService coreInventoryService) {
        this.coreInventoryService = coreInventoryService;
    }

    @Override
    public String holdStock(Long skuId, Integer count, long ttlSeconds) {
        if (count == null || count <= 0) {
            throw new IllegalArgumentException("预占数量必须大于0");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("预占有效期必须大于0");
        }
        if (ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("预占有效期不得超过 " + MAX_TTL_SECONDS + " 秒");
        }

        // delegate 灵核锁定库存
        boolean locked = coreInventoryService.lockStock(skuId, count);
        if (!locked) {
            log.info("Inventory hold failed, stock insufficient. skuId={}, count={}", skuId, count);
            return null;
        }

        String holdId = "HOLD-" + UUID.randomUUID().toString().substring(0, 8);
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000;
        HoldRecord record = new HoldRecord(holdId, skuId, count, expireAt);
        holdRecords.put(holdId, record);

        // 调度超时自动释放：若调度抛 RejectedExecutionException（destroy 后调度器已终止），
        // 须回滚已锁定的核心库存与已存预占单，避免「锁了库存却无自动释放」的永久泄漏。
        try {
            scheduler.schedule(() -> autoExpire(holdId), ttlSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            holdRecords.remove(holdId);
            coreInventoryService.releaseStock(skuId, count);
            log.warn("Inventory hold rolled back: scheduler rejected task. holdId={}, skuId={}, count={}",
                    holdId, skuId, count);
            throw new IllegalStateException("库存预占调度失败，已回滚核心库存锁定", e);
        }

        log.info("Inventory hold created. holdId={}, skuId={}, count={}, ttl={}s, expireAt={}",
                holdId, skuId, count, ttlSeconds, expireAt);
        return holdId;
    }

    @Override
    public boolean confirmDeduct(String holdId) {
        HoldRecord record = holdRecords.get(holdId);
        if (record == null) {
            log.info("Confirm deduct failed, hold not found. holdId={}", holdId);
            return false;
        }
        // 状态检查+扣减+置状态必须原子：防止并发 confirmDeduct 双扣
        synchronized (record) {
            if (record.status != HoldStatus.HOLDING) {
                log.info("Confirm deduct failed, hold not in HOLDING state. holdId={}, status={}",
                        holdId, record.status);
                return false;
            }

            // delegate 灵核扣减锁定库存（将预占转为真实扣减）
            boolean deducted = coreInventoryService.deductLockedStock(record.skuId, record.count);
            if (!deducted) {
                log.warn("Confirm deduct failed, core deduct failed. holdId={}, skuId={}, count={}",
                        holdId, record.skuId, record.count);
                return false;
            }

            record.status = HoldStatus.CONFIRMED;
        }
        log.info("Inventory hold confirmed. holdId={}, skuId={}, count={}", holdId, record.skuId, record.count);
        return true;
    }

    @Override
    public boolean releaseHold(String holdId) {
        HoldRecord record = holdRecords.get(holdId);
        if (record == null) {
            log.info("Release hold failed, hold not found. holdId={}", holdId);
            return false;
        }
        // 状态检查+释放+置状态必须原子：防止与 confirmDeduct/autoExpire 并发双重释放
        synchronized (record) {
            if (record.status != HoldStatus.HOLDING) {
                log.info("Release hold skipped, hold not in HOLDING state. holdId={}, status={}",
                        holdId, record.status);
                return false;
            }

            // delegate 灵核释放库存
            boolean released = coreInventoryService.releaseStock(record.skuId, record.count);
            if (!released) {
                log.warn("Release hold failed, core release failed. holdId={}, skuId={}, count={}",
                        holdId, record.skuId, record.count);
                return false;
            }

            record.status = HoldStatus.RELEASED;
        }
        log.info("Inventory hold released. holdId={}, skuId={}, count={}", holdId, record.skuId, record.count);
        return true;
    }

    @Override
    public String getHoldStatus(String holdId) {
        HoldRecord record = holdRecords.get(holdId);
        if (record == null) {
            return "NOT_FOUND";
        }
        return record.status.name();
    }

    /**
     * 超时自动释放：TTL 到期后若仍未确认扣减，自动释放回可用库存。
     */
    private void autoExpire(String holdId) {
        HoldRecord record = holdRecords.get(holdId);
        if (record == null) {
            return;
        }
        // 状态检查+释放+置状态必须原子：防止与 confirmDeduct/releaseHold 并发
        synchronized (record) {
            if (record.status != HoldStatus.HOLDING) {
                return;
            }
            boolean released = coreInventoryService.releaseStock(record.skuId, record.count);
            record.status = released ? HoldStatus.EXPIRED : HoldStatus.RELEASED;
        }
        log.info("Inventory hold auto-expired. holdId={}, skuId={}, count={}",
                holdId, record.skuId, record.count);
    }

    /**
     * 灵元卸载时由 Spring 容器销毁回调触发。
     * <p>
     * 必须 shutdown 调度器：调度任务的 lambda 闭包持有本实例引用 → 灵元 Class → ClassLoader，
     * 若不释放会阻止灵元 ClassLoader 被 GC。
     * <p>
     * 优雅关闭策略：先 {@code shutdown()}（不中断，让正在执行的 autoExpire 跑完），
     * 等 2 秒；超时再 {@code shutdownNow()}（中断 + 取消未执行任务）。正在执行的 autoExpire 若
     * 卡在灵核 releaseStock 调用上，中断位被设置但是否立即终止取决于灵核实现；最坏情况下
     * 线程在灵核调用返回后才终止，不影响灵核数据一致性（灵核库存由灵核自己维护）。
     */
    @Override
    public void destroy() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        holdRecords.clear();
        log.info("InventoryHoldServiceImpl destroyed: scheduler shutdown, holdRecords cleared");
    }

    /** 预占单据状态 */
    private enum HoldStatus {
        HOLDING, CONFIRMED, RELEASED, EXPIRED
    }

    /** 预占单据记录 */
    private static class HoldRecord {
        final String holdId;
        final Long skuId;
        final Integer count;
        final long expireAt;
        volatile HoldStatus status;

        HoldRecord(String holdId, Long skuId, Integer count, long expireAt) {
            this.holdId = holdId;
            this.skuId = skuId;
            this.count = count;
            this.expireAt = expireAt;
            this.status = HoldStatus.HOLDING;
        }
    }
}
