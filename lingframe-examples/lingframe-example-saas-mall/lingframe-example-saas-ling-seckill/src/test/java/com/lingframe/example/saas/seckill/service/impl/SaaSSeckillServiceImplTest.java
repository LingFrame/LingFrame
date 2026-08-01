package com.lingframe.example.saas.seckill.service.impl;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.example.mall.entity.SeckillActive;
import com.lingframe.example.mall.service.SeckillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SaaSSeckillServiceImpl} 单元测试。
 * <p>
 * 重点覆盖治理语义：租户拦截、租户级秒杀配额（原子检查+扣减、委派失败回滚）、null tenant 空安全。
 */
@DisplayName("SaaSSeckillServiceImpl 单元测试")
class SaaSSeckillServiceImplTest {

    /** 灵元内配额上限与其 private static 一致，此处独立维护作断言基准 */
    private static final int TENANT_SECKILL_QUOTA = 5;

    private void setTenant(String tenantId) {
        Map<String, String> labels = new HashMap<>();
        labels.put("tenant", tenantId);
        LingCallContext.setLabels(labels);
    }

    @AfterEach
    void clearContext() {
        LingCallContext.clear();
    }

    @Nested
    @DisplayName("租户拦截")
    class TenantBlockTest {

        @Test
        @DisplayName("限制性租户直接拦截，不消耗配额、不 delegate 灵核")
        void tenantBlockRejected() {
            SeckillService core = mock(SeckillService.class);
            SaaSSeckillServiceImpl impl = new SaaSSeckillServiceImpl(core);
            try {
                setTenant("tenant_block");
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                        () -> impl.seckill(1L, 100L));
                assertTrue(ex.getMessage().contains("tenant_block"));
                verifyNoInteractions(core);
            } finally {
                impl.destroy();
            }
        }
    }

    @Nested
    @DisplayName("null tenant 空安全")
    class NullTenantTest {

        @Test
        @DisplayName("缺 tenant label 时归并到 default 哈位，不抛 NPE，正常 delegate 灵核")
        void nullTenantFallsBackToDefault() {
            SeckillService core = mock(SeckillService.class);
            when(core.seckill(any(), any())).thenReturn("voucher_ok");
            SaaSSeckillServiceImpl impl = new SaaSSeckillServiceImpl(core);
            try {
                // 不写 tenant label：模拟 X-Tenant-Id 头缺失
                LingCallContext.setLabels(new HashMap<>());
                String voucher = impl.seckill(1L, 100L);
                assertEquals("voucher_ok", voucher);
                verify(core).seckill(1L, 100L);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("null tenant 亦可触发配额上限：default 哈位累计至 QUOTA 后拒")
        void nullTenantQuotaAppliesToDefaultBucket() {
            SeckillService core = mock(SeckillService.class);
            when(core.seckill(any(), any())).thenReturn("v");
            SaaSSeckillServiceImpl impl = new SaaSSeckillServiceImpl(core);
            try {
                LingCallContext.setLabels(new HashMap<>());
                for (int i = 0; i < TENANT_SECKILL_QUOTA; i++) {
                    assertEquals("v", impl.seckill(1L, 100L));
                }
                // 第 QUOTA+1 次应被配额拒
                assertThrows(IllegalArgumentException.class,
                        () -> impl.seckill(1L, 100L),
                        "default 哈位累计至上限后应拒绝");
            } finally {
                impl.destroy();
            }
        }
    }

    @Nested
    @DisplayName("配额委派失败回滚")
    class QuotaRollbackTest {

        @Test
        @DisplayName("灵核委派抛异常时配额回滚：租户不被永久锁外，下次可再次秒杀")
        void quotaRolledBackOnDelegateFailure() {
            SeckillService core = mock(SeckillService.class);
            // 灵核抛异常：模拟「活动不存在」「已抢光」等可达失败路径
            when(core.seckill(any(), any()))
                    .thenThrow(new IllegalArgumentException("活动不存在"))
                    .thenReturn("voucher_ok");
            SaaSSeckillServiceImpl impl = new SaaSSeckillServiceImpl(core);
            try {
                setTenant("tenant_vip");
                // 第一次：灵核抛异常，配额应回滚（租户配额未被消耗）
                assertThrows(IllegalArgumentException.class,
                        () -> impl.seckill(1L, 100L));
                // 第二次：灵核成功，证明配额未被第一次失败永久消耗
                assertEquals("voucher_ok", impl.seckill(1L, 100L));
                verify(core, times(2)).seckill(1L, 100L);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("灵核委派成功时配额不回滚：累计至上限后拒")
        void quotaNotRolledBackOnSuccess() {
            SeckillService core = mock(SeckillService.class);
            when(core.seckill(any(), any())).thenReturn("v");
            SaaSSeckillServiceImpl impl = new SaaSSeckillServiceImpl(core);
            try {
                setTenant("tenant_vip");
                for (int i = 0; i < TENANT_SECKILL_QUOTA; i++) {
                    assertEquals("v", impl.seckill(1L, 100L));
                }
                assertThrows(IllegalArgumentException.class,
                        () -> impl.seckill(1L, 100L),
                        "配额用尽后应拒绝");
            } finally {
                impl.destroy();
            }
        }
    }

    @Nested
    @DisplayName("并发秒杀配额不超限")
    class ConcurrencyTest {

        @Test
        @DisplayName("多线程并发秒杀同一租户：配额上限严格成立，不冲破 TENANT_SECKILL_QUOTA")
        void concurrentSeckillDoesNotExceedQuota() throws InterruptedException {
            SeckillService core = mock(SeckillService.class);
            when(core.seckill(any(), any())).thenReturn("v");
            SaaSSeckillServiceImpl impl = new SaaSSeckillServiceImpl(core);
            try {
                setTenant("tenant_vip");
                int threads = 50;
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch fire = new CountDownLatch(1);
                AtomicInteger success = new AtomicInteger();
                AtomicInteger rejected = new AtomicInteger();
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            fire.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            impl.seckill(1L, 100L);
                            success.incrementAndGet();
                        } catch (IllegalArgumentException ex) {
                            rejected.incrementAndGet();
                        }
                    });
                }
                ready.await();
                fire.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

                assertEquals(TENANT_SECKILL_QUOTA, success.get(),
                        "成功次数应严格等于配额上限，不允许并发冲破");
                assertEquals(threads - TENANT_SECKILL_QUOTA, rejected.get(),
                        "超额请求应被配额拒绝");
            } finally {
                impl.destroy();
            }
        }
    }
}
