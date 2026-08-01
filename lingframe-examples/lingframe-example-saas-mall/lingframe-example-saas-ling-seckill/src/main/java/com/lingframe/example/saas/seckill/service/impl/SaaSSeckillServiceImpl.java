package com.lingframe.example.saas.seckill.service.impl;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.example.mall.entity.SeckillActive;
import com.lingframe.example.mall.service.SeckillService;
import com.lingframe.infra.mybatisplus.DelegatingIServiceSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SaaS 商城秒杀灵元——拓展灵核 {@link SeckillService} 实现。
 * <p>
 * 设计要点（绞杀迁移示例之二：拓展原接口）：
 * <ul>
 *   <li>灵元与灵核 {@code SeckillServiceImpl} 共同实现同一契约 {@link SeckillService}，
 *       由路由层按 provider 权重在灵核/灵元之间切流，Dashboard 调权重即可完成流量迁移。</li>
 *   <li>灵元不持有 DataSource，IService 桩方法由 {@link DelegatingIServiceSupport} 统一 delegate 到灵核，
 *       子类只写拓展点，零桩代码。</li>
 *   <li>拓展点：{@link #seckill} 在灵核实现之上叠加 SaaS 多租户治理——
 *       限制性租户拦截 + 租户级秒杀配额预检（灵核版本只有全局库存，无租户维度）。</li>
 *   <li>非拓展点（querySeckillStatus）：delegate 到灵核，秒杀异步下单结果由灵核 VOUCHER_RESULTS 维护。</li>
 * </ul>
 * tenantId 不进方法签名，由 HTTP 入口写入请求头 label，路由层精准命中灵元 provider。
 */
@Slf4j
@Component
public class SaaSSeckillServiceImpl extends DelegatingIServiceSupport<SeckillActive>
        implements SeckillService, DisposableBean {

    /**
     * 显式 pinning 到灵核：避免灵元→灵元自调用循环。
     * lingId 固定为灵核 ID，路由层不再做双 provider 切流，直接命中灵核 SeckillServiceImpl。
     */
    @LingReference(lingId = "lingcore-app")
    private SeckillService coreSeckillService;

    /**
     * Spring 用的无参构造器：显式声明，创建后由 {@code LingReferenceInjector} BPP 注入 {@code @LingReference} 字段。
     */
    public SaaSSeckillServiceImpl() {
    }

    /**
     * 测试用构造器：包私有，直接传入 mock 灵核代理，避免反射注入 private 字段。
     * <p>
     * 生产环境由无参构造 + BPP 注入；{@code LingReferenceInjector} 对非 null 字段跳过注入。
     */
    SaaSSeckillServiceImpl(SeckillService coreSeckillService) {
        this.coreSeckillService = coreSeckillService;
    }

    /**
     * 租户级秒杀配额计数器：拓展灵核缺失的租户维度。
     * 灵核版本只有全局库存预减，灵元版本在此基础上加每租户秒杀名额上限。
     * <p>
     * <b>必须为实例字段</b>：若为 static，灵元卸载后 ClassLoader 仍被 {@code ConcurrentHashMap}
     * → {@code AtomicInteger}（灵元类）引用链持有，阻止 GC 回收灵元 ClassLoader。
     * 改为实例字段后，由 {@link #destroy()} 在灵元卸载时清空。
     */
    private final Map<String, AtomicInteger> tenantSeckillCount = new ConcurrentHashMap<>();
    private static final int TENANT_SECKILL_QUOTA = 5;

    @Override
    protected IService<SeckillActive> getCoreService() {
        return coreSeckillService;
    }

    /**
     * 拓展灵核 seckill：叠加 SaaS 多租户治理。
     * <p>
     * 灵核版本：全局库存预减 + 削峰队列异步下单。
     * 灵元版本：限制性租户拦截 + 租户级配额预检，再 delegate 灵核完成核心削峰逻辑。
     */
    @Override
    public String seckill(Long userId, Long activeId) {
        String tenantId = LingCallContext.getLabels().get("tenant");
        if ("tenant_block".equals(tenantId)) {
            throw new IllegalArgumentException("租户 " + tenantId + " 秒杀服务已被管理员限制访问");
        }
        // tenantId 可能为 null（HTTP 入口 X-Tenant-Id 头可选）：ConcurrentHashMap 不允许 null 键，
        // 故显式 guard——匿名/未标注租户归并到 "default" 哨位，与 SaaSUserServiceImpl.socialLogin 的空安全策略一致。
        String quotaKey = tenantId != null ? tenantId : "default";
        // 租户级配额预检：每租户最多 TENANT_SECKILL_QUOTA 次秒杀
        AtomicInteger count = tenantSeckillCount.computeIfAbsent(quotaKey, k -> new AtomicInteger(0));
        // CAS 原子化「检查 + 扣减」：防止并发秒杀下两线程同时通过 >= QUOTA 检查后双扣，配额冲破上限
        int consumed;
        while (true) {
            int current = count.get();
            if (current >= TENANT_SECKILL_QUOTA) {
                throw new IllegalArgumentException("租户 " + quotaKey + " 秒杀配额已用尽");
            }
            if (count.compareAndSet(current, current + 1)) {
                consumed = current + 1;
                break;
            }
        }
        log.info("SaaS seckill override. tenant={}, userId={}, activeId={}, tenantCount={}",
                quotaKey, userId, activeId, consumed);
        try {
            // delegate 到灵核完成活动查询、库存预减、削峰队列异步下单
            return coreSeckillService.seckill(userId, activeId);
        } catch (RuntimeException e) {
            // 委派失败回滚配额：核心秒杀抛异常时（活动不存在/未开始/已抢光/排队失败）配额不应被消耗，
            // 否则租户会被永久锁外却从未成功拿到秒杀slot
            count.decrementAndGet();
            throw e;
        }
    }

    /**
     * delegate 灵核：秒杀异步下单结果由灵核 VOUCHER_RESULTS 维护，灵元无需重复实现。
     */
    @Override
    public Long querySeckillStatus(Long userId, String voucher) {
        return coreSeckillService.querySeckillStatus(userId, voucher);
    }

    /**
     * 灵元卸载时由 Spring 容器销毁回调触发：清空租户配额计数器，断开实例引用链，辅助 ClassLoader GC。
     */
    @Override
    public void destroy() {
        tenantSeckillCount.clear();
        log.info("SaaSSeckillServiceImpl destroyed: tenantSeckillCount cleared");
    }
}
