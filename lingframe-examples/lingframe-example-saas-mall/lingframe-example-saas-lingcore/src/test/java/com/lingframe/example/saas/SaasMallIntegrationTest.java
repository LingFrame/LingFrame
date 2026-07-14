package com.lingframe.example.saas;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.entity.Order;
import com.lingframe.example.mall.entity.OrderRefund;
import com.lingframe.example.mall.mapper.OrderMapper;
import com.lingframe.example.mall.mapper.OrderRefundMapper;
import com.lingframe.example.saas.api.dto.OAuthRenderResult;
import com.lingframe.example.saas.api.dto.SeckillResult;
import com.lingframe.example.saas.api.dto.SeckillStatusResult;
import com.lingframe.example.saas.controller.SaasAuthController;
import com.lingframe.example.saas.controller.SaasRefundController;
import com.lingframe.example.saas.controller.SaasSeckillController;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.io.File;
import java.math.BigDecimal;
import java.util.Collections;

/**
 * SaaS 商城灵核 + 多灵元协作集成测试。
 * <p>
 * 灵珑主张「灵核通过 {@code @LingReference} 持有灵元代理，没有生产者就快速失败」，
 * 因此本测试不再轮询灵元就绪状态——Spring Boot 启动完成时，
 * {@code LingReferenceInjector}（BPP）已把灵元代理织入控制器字段，直接调用即可；
 * 若灵元未装，调用会立即 NPE/快速失败，这正是灵珑期望的语义。
 */
@SpringBootTest(classes = SaasMallApplication.class)
@Slf4j
public class SaasMallIntegrationTest {

    @Autowired
    private SaasAuthController authController;

    @Autowired
    private SaasSeckillController seckillController;

    @Autowired
    private SaasRefundController refundController;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderRefundMapper orderRefundMapper;

    @BeforeEach
    void setUpSecurityContext() {
        // 灵核 SaasRefundController.auditRefund 标 @PreAuthorize("hasAuthority('order:admin:refundAudit')")，
        // 测试直调控制器方法需塞认证上下文绕过 Spring Security 方法级拦。
        GrantedAuthority authority = new SimpleGrantedAuthority("order:admin:refundAudit");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-admin", "n/a", Collections.singleton(authority));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("SaaS 灵元加载与运行时代理协作集成验证")
    public void testSaaSInterations() throws Exception {
        log.info("SaaS Integration Test: checking dynamic ability routing...");

        // 1. 验证三方登录灵元 (OAuthAbility) 动态多租户路由
        ResponseResult<OAuthRenderResult> renderRes = authController.socialRender("tenant_vip", "gitee");
        log.info("Social Render Response: {}", renderRes);
        Assertions.assertEquals(200, renderRes.getCode(), "三方登录灵元协作路由失败");
        Assertions.assertTrue(renderRes.getData().getRedirectUrl().contains("gitee"), "OAuth 能力回调路径错误");

        // 2. 验证限制性租户社交登录拦截
        // 灵珑流水线把灵元实现类抛的业务异常统一包装为 LingInvocationException(LING-5001 INVOKE_ERROR)，
        // 原 IllegalArgumentException 作为 cause 透传，断言需穿透包装层校验业务语义。
        try {
            authController.socialRender("tenant_block", "gitee");
            Assertions.fail("限制性租户应该被拒绝社交登录");
        } catch (LingInvocationException e) {
            Assertions.assertEquals(LingInvocationException.ErrorKind.INVOKE_ERROR, e.getKind(),
                    "限制性租户应触发业务执行异常");
            Assertions.assertTrue(e.getCause() instanceof IllegalArgumentException,
                    "底层业务异常类型应为 IllegalArgumentException");
            Assertions.assertTrue(e.getCause().getMessage().contains("限制访问"),
                    "限制性租户报错内容不匹配: " + e.getCause().getMessage());
        }

        // 3. 验证秒杀促销灵元 (SeckillAbility) 动态多租户路由
        ResponseResult<SeckillResult> seckillRes = seckillController.doSeckill("tenant_vip", 1L);
        log.info("Seckill Response: {}", seckillRes);
        Assertions.assertEquals(200, seckillRes.getCode(), "秒杀削峰灵元协作路由失败");
        Assertions.assertNotNull(seckillRes.getData().getVoucher(), "秒杀排队凭证生成为空");

        // 等待削峰队列异步下单执行
        Thread.sleep(2000);

        String voucher = seckillRes.getData().getVoucher();
        ResponseResult<SeckillStatusResult> statusRes = seckillController.getSeckillStatus("tenant_vip", voucher);
        log.info("Seckill Status Response: {}", statusRes);
        Assertions.assertEquals("SUCCESS", statusRes.getData().getStatus(), "秒杀异步下单写库失败");

        // 4. 验证退款规则拦截器 (RefundPolicy) 动态租户级策略路由与拦截分流
        // 4.1 普通租户（Standard Tenant）分流测试：无法触发极速秒退，应当落入普通人工审核流程
        Order orderStd = new Order();
        orderStd.setOrderSn("SN_SAAS_STD_999");
        orderStd.setUserId(2L);
        orderStd.setTotalAmount(new BigDecimal("500.00"));
        orderStd.setStatus(1);
        orderStd.setReceiverName("StandardTenantReceiver");
        orderStd.setReceiverPhone("13800000001");
        orderStd.setReceiverAddress("Standard Tenant Address");
        orderMapper.insert(orderStd);

        OrderRefund refundStd = new OrderRefund();
        refundStd.setOrderId(orderStd.getId());
        refundStd.setUserId(2L);
        refundStd.setAmount(new BigDecimal("500.00"));
        refundStd.setReason("Standard 售后普通退款");
        refundStd.setStatus(0);
        orderRefundMapper.insert(refundStd);

        ResponseResult<String> auditResStd = refundController.auditRefund("tenant_standard", refundStd.getId(), 1, null);
        log.info("Standard Tenant Refund Approval Response: {}", auditResStd);
        // 断言: 普通租户返回“人工管理员完成审批”
        Assertions.assertTrue(auditResStd.getData().contains("人工管理员"), "普通租户不应触发VIP秒退拦截");

        // 4.2 VIP 租户 (KA Tenant) 定制分流测试：触发 VipRefundPolicy 规则自动秒退
        Order orderVip = new Order();
        orderVip.setOrderSn("SN_SAAS_VIP_999");
        orderVip.setUserId(2L);
        orderVip.setTotalAmount(new BigDecimal("500.00"));
        orderVip.setStatus(1);
        orderVip.setReceiverName("VipTenantReceiver");
        orderVip.setReceiverPhone("13800000002");
        orderVip.setReceiverAddress("VIP Tenant Address");
        orderMapper.insert(orderVip);

        OrderRefund refundVip = new OrderRefund();
        refundVip.setOrderId(orderVip.getId());
        refundVip.setUserId(2L);
        refundVip.setAmount(new BigDecimal("500.00"));
        refundVip.setReason("VIP 极速秒退");
        refundVip.setStatus(0);
        orderRefundMapper.insert(refundVip);

        ResponseResult<String> auditResVip = refundController.auditRefund("tenant_vip", refundVip.getId(), 1, null);
        log.info("VIP Tenant Refund Approval Response: {}", auditResVip);
        // 断言: VIP租户返回“极速退款灵元全自动拦截并处理同意”
        Assertions.assertTrue(auditResVip.getData().contains("极速退款灵元"), "VIP租户极速秒退策略路由失效");

        OrderRefund updatedRefund = orderRefundMapper.selectById(refundVip.getId());
        Assertions.assertEquals(1, updatedRefund.getStatus(), "定制退款审核状态未变更为已同意");
    }
}
