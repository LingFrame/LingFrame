package com.lingframe.example.saas;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.example.mall.entity.SeckillActive;
import com.lingframe.example.mall.service.SeckillService;
import com.lingframe.example.mall.service.UserService;
import com.lingframe.example.saas.inventory.service.InventoryHoldService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * SaaS 商城灵核 + 多灵元契约级切流集成测试。
 * <p>
 * 验证「绞杀迁移」核心机制：
 * <ul>
 *   <li>覆盖维度：OAuth 灵元覆盖灵核 UserService.socialLogin，叠加 SaaS 多租户治理</li>
 *   <li>拓展维度：Seckill 灵元拓展灵核 SeckillService.seckill，叠加租户级配额预检</li>
 *   <li>新增维度：InventoryHold 灵元提供灵核不存在的带 TTL 库存预占服务</li>
 *   <li>卸载回退：灵元卸载后流量自动回退灵核（双 provider 场景）</li>
 *   <li>配额耗尽：Seckill 灵元租户级配额达到上限后抛异常</li>
 *   <li>定时卸载：InventoryHold 灵元定时任务触发后卸载，验证 scheduler 线程终止无泄漏</li>
 * </ul>
 * tenantId 通过 {@link LingCallContext} 的 label 传递，灵元内部从 label 读取做租户级决策。
 * <p>
 * 测试顺序：卸载测试放最后（{@link #testUnloadLingFallbackToCore}），卸载后灵元不可恢复，
 * 用 {@link TestMethodOrder} 保证卸载在切流验证之后执行。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "lingframe.dashboard.storage.path=${java.io.tmpdir}/lingframe-mall-test-dashboard.db",
        "lingframe.governance-patch-path=${java.io.tmpdir}/lingframe-mall-test-patch.yml"
})
@SpringBootTest(classes = SaasMallApplication.class)
@Slf4j
@DisplayName("SaaS 商城灵核 + 多灵元契约级切流集成测试")
public class SaasMallIntegrationTest {

    private static final String USER_CONTRACT = "com.lingframe.example.mall.service.UserService";
    private static final String SECKILL_CONTRACT = "com.lingframe.example.mall.service.SeckillService";
    private static final String OAUTH_LING_ID = "saas-oauth-ling";
    private static final String SECKILL_LING_ID = "saas-seckill-ling";
    private static final String INVENTORY_LING_ID = "saas-inventory-hold-ling";
    private static final String CORE_ID = LingCoreConstants.LINGCORE_LING_ID;

    // 不指定 lingId：走契约的双 provider 权重切流
    @LingReference
    private UserService userService;
    @LingReference
    private SeckillService seckillService;
    // 灵元唯一 provider（灵核无此契约），不指定 lingId 直接命中灵元
    @LingReference
    private InventoryHoldService inventoryHoldService;

    @Autowired
    private ProviderWeightRouter providerWeightRouter;
    @Autowired
    private LingLifecycleEngine lingLifecycleEngine;
    @Autowired
    private LingRepository lingRepository;
    @Autowired
    private com.lingframe.example.mall.mapper.SeckillActiveMapper seckillActiveMapper;

    @AfterEach
    void restore() {
        LingCallContext.clear();
        clearWeight(USER_CONTRACT, OAUTH_LING_ID);
        clearWeight(SECKILL_CONTRACT, SECKILL_LING_ID);
    }

    @Test
    @Order(1)
    @DisplayName("OAuth 灵元覆盖 socialLogin：默认走灵核，切流后走灵元 SaaS 多租户治理")
    public void testOAuthLingRouting() {
        // 1. 默认权重（灵核 100/灵元 0）：走灵核 UserServiceImpl.socialLogin
        String token = userService.socialLogin("gitee", "core_openId_1", "nick1", "avatar1");
        Assertions.assertNotNull(token, "灵核 socialLogin 应返回 token");
        log.info("默认权重走灵核 socialLogin 成功, token={}", token);

        // 2. 切流到灵元（灵核 0/灵元 100）
        switchToLing(USER_CONTRACT, OAUTH_LING_ID);

        // 2.1 限制性租户：灵元覆盖点拦截
        setTenantLabel("tenant_block");
        LingInvocationException ex = Assertions.assertThrows(LingInvocationException.class,
                () -> userService.socialLogin("gitee", "block_openId", "nick", "avatar"));
        Assertions.assertEquals(LingInvocationException.ErrorKind.INVOKE_ERROR, ex.getKind(),
                "限制性租户应触发业务执行异常");
        Assertions.assertTrue(ex.getCause() instanceof IllegalArgumentException,
                "底层业务异常类型应为 IllegalArgumentException");
        Assertions.assertTrue(ex.getCause().getMessage().contains("限制访问"),
                "限制性租户报错内容不匹配: " + ex.getCause().getMessage());
        log.info("切流后 tenant_block 被灵元覆盖点拦截");

        // 2.2 正常租户：灵元 openId 加租户前缀，delegate 灵核完成绑定
        setTenantLabel("tenant_vip");
        String token2 = userService.socialLogin("gitee", "vip_openId_1", "nick2", "avatar2");
        Assertions.assertNotNull(token2, "灵元 socialLogin delegate 灵核应返回 token");
        log.info("切流后 tenant_vip 灵元 socialLogin delegate 灵核成功, token={}", token2);
    }

    @Test
    @Order(2)
    @DisplayName("Seckill 灵元拓展 seckill：默认走灵核，切流后走灵元 SaaS 多租户配额治理")
    public void testSeckillLingRouting() {
        // 预插入秒杀活动数据（异步下单依赖 SKU/库存，本测试只验证 voucher 生成与切流，不验证异步下单写库）
        SeckillActive active = new SeckillActive();
        active.setSpuId(1L);
        active.setSkuId(1L);
        active.setSeckillPrice(new BigDecimal("9.90"));
        active.setStock(100);
        active.setStartTime(new Date(System.currentTimeMillis() - 60_000));
        active.setEndTime(new Date(System.currentTimeMillis() + 60_000));
        seckillActiveMapper.insert(active);
        log.info("预插入秒杀活动, activeId={}", active.getId());

        // 1. 默认权重：走灵核 SeckillServiceImpl.seckill
        String voucher = seckillService.seckill(1L, active.getId());
        Assertions.assertNotNull(voucher, "灵核 seckill 应返回 voucher");
        log.info("默认权重走灵核 seckill 成功, voucher={}", voucher);

        // 2. 切流到灵元
        switchToLing(SECKILL_CONTRACT, SECKILL_LING_ID);

        // 2.1 限制性租户：灵元拓展点拦截
        setTenantLabel("tenant_block");
        LingInvocationException ex = Assertions.assertThrows(LingInvocationException.class,
                () -> seckillService.seckill(2L, active.getId()));
        Assertions.assertTrue(ex.getCause() instanceof IllegalArgumentException,
                "限制性租户底层异常类型应为 IllegalArgumentException");
        Assertions.assertTrue(ex.getCause().getMessage().contains("限制访问"),
                "限制性租户报错内容不匹配: " + ex.getCause().getMessage());
        log.info("切流后 tenant_block 被灵元拓展点拦截");

        // 2.2 正常租户：灵元配额预检 + delegate 灵核
        setTenantLabel("tenant_vip");
        String voucher2 = seckillService.seckill(3L, active.getId());
        Assertions.assertNotNull(voucher2, "灵元 seckill delegate 灵核应返回 voucher");
        log.info("切流后 tenant_vip 灵元 seckill delegate 灵核成功, voucher={}", voucher2);

        // 验证 querySeckillStatus 也走灵元 delegate 灵核（结果可能为 null/排队中或 -1/失败，不断言具体值）
        Long orderId = seckillService.querySeckillStatus(3L, voucher2);
        log.info("灵元 querySeckillStatus delegate 灵核, orderId={}", orderId);
    }

    @Test
    @Order(3)
    @DisplayName("InventoryHold 灵元新增能力：预占/确认扣减/释放/状态查询全链路")
    public void testInventoryHoldLingNewCapability() {
        // 灵元是唯一 provider（灵核无此契约），直接命中灵元
        // 1. 预占库存（TTL 60 秒，足够测试完成）
        String holdId = inventoryHoldService.holdStock(1L, 5, 60);
        Assertions.assertNotNull(holdId, "预占应返回 holdId");
        log.info("库存预占成功, holdId={}", holdId);

        // 2. 查询预占状态：HOLDING
        String status = inventoryHoldService.getHoldStatus(holdId);
        Assertions.assertEquals("HOLDING", status, "预占后状态应为 HOLDING");
        log.info("预占状态查询: {}", status);

        // 3. 确认扣减：转为 CONFIRMED
        boolean confirmed = inventoryHoldService.confirmDeduct(holdId);
        Assertions.assertTrue(confirmed, "确认扣减应成功");
        Assertions.assertEquals("CONFIRMED", inventoryHoldService.getHoldStatus(holdId),
                "确认扣减后状态应为 CONFIRMED");
        log.info("确认扣减成功, holdId={}", holdId);

        // 4. 重复确认扣减：应失败（状态已变）
        boolean reConfirm = inventoryHoldService.confirmDeduct(holdId);
        Assertions.assertFalse(reConfirm, "重复确认扣减应失败");
        log.info("重复确认扣减正确返回 false");

        // 5. 释放已确认的单据：应失败（状态非 HOLDING）
        boolean release = inventoryHoldService.releaseHold(holdId);
        Assertions.assertFalse(release, "释放已确认单据应失败");
        log.info("释放已确认单据正确返回 false");

        // 6. 查询不存在的单据
        Assertions.assertEquals("NOT_FOUND", inventoryHoldService.getHoldStatus("NOT_EXIST"),
                "不存在单据应返回 NOT_FOUND");
    }

    @Test
    @Order(4)
    @DisplayName("灵元卸载回退：卸载 OAuth 灵元后流量自动回退灵核")
    public void testUnloadLingFallbackToCore() {
        // 1. 切流到灵元，验证灵元覆盖点生效
        switchToLing(USER_CONTRACT, OAUTH_LING_ID);
        setTenantLabel("tenant_block");
        LingInvocationException ex = Assertions.assertThrows(LingInvocationException.class,
                () -> userService.socialLogin("gitee", "verify_openId", "nick", "avatar"));
        Assertions.assertTrue(ex.getCause() instanceof IllegalArgumentException,
                "卸载前灵元覆盖点应拦截 tenant_block");
        log.info("卸载前验证：灵元覆盖点生效");

        // 2. 卸载灵元（同步阻塞，等飞行请求排空）
        lingLifecycleEngine.undeploy(OAUTH_LING_ID);
        Assertions.assertFalse(lingRepository.hasRuntime(OAUTH_LING_ID),
                "卸载后灵元运行时应已移除");
        log.info("OAuth 灵元卸载完成");

        // 3. 卸载后调用：灵元已移除，灵核成为唯一 provider，流量自动回退灵核
        //    tenant_block 不再被灵元拦截（灵核无此治理），正常返回 token
        setTenantLabel("tenant_block");
        String token = userService.socialLogin("gitee", "fallback_openId", "nick", "avatar");
        Assertions.assertNotNull(token, "卸载灵元后应回退灵核 socialLogin 返回 token");
        log.info("卸载后流量回退灵核成功, token={}", token);

        // 4. 验证灵核原生行为：无 tenant_block 拦截（灵核无多租户治理）
        //    openId 不加租户前缀（灵核无命名空间隔离）
        LingCallContext.clear();
        String token2 = userService.socialLogin("gitee", "raw_openId", "nick", "avatar");
        Assertions.assertNotNull(token2, "灵核 socialLogin 应返回 token");
        log.info("灵核原生 socialLogin 验证成功, token={}", token2);
    }

    @Test
    @Order(5)
    @DisplayName("Seckill 灵元配额耗尽：租户级配额达到上限后抛异常")
    public void testSeckillQuotaExhausted() {
        // 预插入秒杀活动
        SeckillActive active = new SeckillActive();
        active.setSpuId(2L);
        active.setSkuId(2L);
        active.setSeckillPrice(new BigDecimal("19.90"));
        active.setStock(100);
        active.setStartTime(new Date(System.currentTimeMillis() - 60_000));
        active.setEndTime(new Date(System.currentTimeMillis() + 60_000));
        seckillActiveMapper.insert(active);
        log.info("预插入秒杀活动, activeId={}", active.getId());

        // 切流到灵元
        switchToLing(SECKILL_CONTRACT, SECKILL_LING_ID);
        // 用独立租户，避免受 testSeckillLingRouting 的 tenant_vip 计数影响
        setTenantLabel("tenant_quota");

        // 调 5 次成功（TENANT_SECKILL_QUOTA=5）
        for (int i = 0; i < 5; i++) {
            String voucher = seckillService.seckill(100L + i, active.getId());
            Assertions.assertNotNull(voucher, "第 " + (i + 1) + " 次秒杀应成功");
        }
        log.info("租户 tenant_quota 已用尽 5 次配额");

        // 第 6 次抛配额耗尽异常
        LingInvocationException ex = Assertions.assertThrows(LingInvocationException.class,
                () -> seckillService.seckill(106L, active.getId()));
        Assertions.assertTrue(ex.getCause() instanceof IllegalArgumentException,
                "配额耗尽底层异常类型应为 IllegalArgumentException");
        Assertions.assertTrue(ex.getCause().getMessage().contains("配额已用尽"),
                "配额耗尽报错内容不匹配: " + ex.getCause().getMessage());
        log.info("配额耗尽正确抛出异常");
    }

    @Test
    @Order(6)
    @DisplayName("定时触发后灵元卸载：autoExpire 执行后卸载，验证 scheduler 线程终止")
    public void testUnloadAfterScheduledTask() throws InterruptedException {
        // 1. 创建短 TTL 预占（1 秒），触发定时调度
        String holdId = inventoryHoldService.holdStock(1L, 2, 1);
        Assertions.assertNotNull(holdId, "预占应返回 holdId");
        log.info("创建短 TTL 预占, holdId={}", holdId);

        // 2. 等待 autoExpire 调度执行（TTL=1s，等 2s 确保触发）
        Thread.sleep(2000);
        Assertions.assertEquals("EXPIRED", inventoryHoldService.getHoldStatus(holdId),
                "TTL 到期后状态应为 EXPIRED，证明定时任务已触发");
        log.info("定时任务已触发，预占状态变 EXPIRED");

        // 3. 验证定时器线程存活（灵元还在运行）
        Assertions.assertTrue(isInventoryHoldThreadAlive(),
                "卸载前 inventory-hold-expiry 线程应存活");
        log.info("卸载前定时器线程存活");

        // 4. 卸载灵元（同步阻塞，等飞行请求排空 + context.close 触发 destroy）
        lingLifecycleEngine.undeploy(INVENTORY_LING_ID);
        Assertions.assertFalse(lingRepository.hasRuntime(INVENTORY_LING_ID),
                "卸载后 inventory 灵元运行时应已移除");
        log.info("Inventory 灵元卸载完成");

        // 5. 等待线程终止 + 验证（destroy() 调 scheduler.shutdownNow，线程应终止）
        Thread.sleep(500);
        Assertions.assertFalse(isInventoryHoldThreadAlive(),
                "卸载后 inventory-hold-expiry 线程应已终止（destroy() shutdown scheduler），无 ClassLoader 泄漏");
        log.info("卸载后定时器线程已终止，无资源泄漏");
    }

    /** 检查 inventory-hold-expiry 调度器线程是否存活 */
    private boolean isInventoryHoldThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("inventory-hold-expiry") && t.isAlive());
    }

    private void switchToLing(String contract, String lingId) {
        providerWeightRouter.setProviderWeight(contract, lingId, 100);
        providerWeightRouter.setProviderWeight(contract, CORE_ID, 0);
    }

    private void clearWeight(String contract, String lingId) {
        providerWeightRouter.clearProviderWeight(contract, lingId);
        providerWeightRouter.clearProviderWeight(contract, CORE_ID);
    }

    private void setTenantLabel(String tenantId) {
        Map<String, String> labels = new HashMap<>();
        labels.put("tenant", tenantId);
        LingCallContext.setLabels(labels);
    }
}
