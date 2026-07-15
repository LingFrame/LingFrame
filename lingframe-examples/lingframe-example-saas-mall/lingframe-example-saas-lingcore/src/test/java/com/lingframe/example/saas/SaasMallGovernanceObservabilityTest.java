package com.lingframe.example.saas;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.ProviderDescriptor;
import com.lingframe.core.router.ProviderWeightRouter;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.saas.api.dto.OAuthRenderResult;
import com.lingframe.example.saas.controller.SaasAuthController;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.Collections;
import java.util.List;

/**
 * SaaS 商城运行时治理可观测性集成测试。
 * <p>
 * 演示灵珑「运行时治理五件套」中限流与治理链路顺序的可观测效果。
 * <p>
 * <b>关键设计点</b>：灵核底座 {@code OAuthAbilityCoreImpl} 注册为 CORE provider（权重=100），
 * 灵元 {@code OAuthAbilityImpl} 注册为 LING provider（权重=0）。
 * 默认流量按权重走灵核，灵核治理被 {@code ling-core-governance.enabled=false} 禁用。
 * <p>
 * 本测试演示<b>Dashboard 灰度引流</b>的真实路径：通过 {@link ProviderWeightRouter#setProviderWeight}
 * 把灵元 provider 权重调到 100、灵核 provider 权重降到 0，让流量 100% 切换到灵元，治理补丁下发到灵元后限流即触发。
 * 这正是灵珑「二维路由 + 治理」的设计语义——治理随流量走，Dashboard 控制面统一调配。
 * <p>
 * <b>令牌桶状态注意</b>：{@code TokenBucketRateLimiter} 按 {@code rateLimitPerSecond=1} 每秒补充 1 个令牌。
 * 同一 {@code @SpringBootTest} 共享单例限流器，跨测试方法令牌不重置。
 * 因此限流相关断言集中在同一个测试方法内完成，避免跨方法令牌竞争；
 * 非限流断言（provider 路由权重）放独立方法，不消耗令牌。
 */
// 测试场景下 dashboard SQLite 路径指向系统临时目录，避免：
// 1. 污染用户家目录（${user.home}/.lingframe/）
// 2. 全量 mvn test 时跨模块 surefire fork 进程对同一文件的句柄竞争（Windows "拒绝访问"）
// 测试逻辑不依赖 dashboard 持久化，但 Spring Boot 启动需 storage bean 可写
@TestPropertySource(properties = "lingframe.dashboard.storage.path=${java.io.tmpdir}/lingframe-mall-test-dashboard.db")
@SpringBootTest(classes = SaasMallApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@DisplayName("SaaS 商城运行时治理可观测性测试")
public class SaasMallGovernanceObservabilityTest {

    private static final String OAUTH_LING_ID = "saas-oauth-ling";
    private static final String OAUTH_CONTRACT = "com.lingframe.example.saas.api.OAuthAbility";

    @Autowired
    private SaasAuthController authController;

    @Autowired
    private LocalGovernanceRegistry governanceRegistry;

    @Autowired
    private LingServiceRegistry serviceRegistry;

    @Autowired
    private ProviderWeightRouter providerWeightRouter;

    /**
     * 唯一一次性 setup：下发治理补丁 + 灰度引流配置。
     * <p>
     * 放在 {@code @BeforeAll} 而非 {@code @BeforeEach}，避免每个测试方法都重置令牌桶
     * （重置后桶会重新装满，破坏限流断言的连续性）。
     */
    @BeforeAll
    void setUpGovernance() {
        // 1. Dashboard 灰度引流：把灵元 provider 权重调到 100，流量切换到灵元
        providerWeightRouter.setProviderWeight(OAUTH_CONTRACT, OAUTH_LING_ID, 100);
        // 灵核 provider 权重降为 0，确保流量 100% 切到灵元（否则按默认权重 50/50 分流，限流断言 flaky）
        providerWeightRouter.setProviderWeight(OAUTH_CONTRACT, LingCoreConstants.LINGCORE_LING_ID, 0);
        log.info("Provider weight overridden: {} ling={} weight=100, core={} weight=0", OAUTH_CONTRACT, OAUTH_LING_ID, LingCoreConstants.LINGCORE_LING_ID);

        // 2. 治理补丁下发到灵元（现在灵元接流量）
        GovernancePolicy lingPatch = new GovernancePolicy();
        GovernancePolicy.InvocationPolicy invocation = new GovernancePolicy.InvocationPolicy();
        invocation.setRateLimitPerSecond(1);
        invocation.setMaxConcurrentThreads(1);
        invocation.setTimeoutMs(2000);
        lingPatch.setInvocation(invocation);
        governanceRegistry.updatePatch(OAUTH_LING_ID, lingPatch);
        log.info("Governance patch applied to ling [{}]: rateLimit=1/s", OAUTH_LING_ID);

        // 3. 等待令牌桶初始装满（构造时已装满，但保险起见 sleep 一个补充周期）
        try {
            Thread.sleep(1100);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    void tearDownGovernance() {
        // 恢复默认权重，避免污染其他测试类
        providerWeightRouter.clearProviderWeight(OAUTH_CONTRACT, OAUTH_LING_ID);
        providerWeightRouter.clearProviderWeight(OAUTH_CONTRACT, LingCoreConstants.LINGCORE_LING_ID);
    }

    @BeforeEach
    void setUpSecurityContext() {
        GrantedAuthority authority = new SimpleGrantedAuthority("order:admin:refundAudit");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-admin", "n/a", Collections.singleton(authority));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("治理前置条件：Dashboard 灰度引流")
    class ProviderRoutingPreconditionTest {

        @Test
        @DisplayName("灵元 provider 权重覆盖为 100，灵核降为 0——流量 100% 切到灵元")
        public void testLingProviderWeightOverridden() {
            List<ProviderDescriptor> providers =
                    serviceRegistry.getProvidersByContractId(OAUTH_CONTRACT);
            Assertions.assertEquals(2, providers.size(),
                    "OAuth 契约应有灵核 + 灵元两个 provider");

            Integer override = providerWeightRouter.getOverrideWeight(OAUTH_CONTRACT, OAUTH_LING_ID);
            Assertions.assertEquals(100, override,
                    "Dashboard 覆盖后灵元 provider 权重应为 100");

            Integer coreOverride = providerWeightRouter.getOverrideWeight(OAUTH_CONTRACT, LingCoreConstants.LINGCORE_LING_ID);
            Assertions.assertEquals(0, coreOverride,
                    "Dashboard 覆盖后灵核 provider 权重应为 0，流量 100% 切灵元");
            log.info("Provider weight override confirmed: ling={} weight=100, core={} weight=0",
                    OAUTH_LING_ID, LingCoreConstants.LINGCORE_LING_ID);
        }
    }

    @Nested
    @DisplayName("限流治理：灵元 rateLimitPerSecond=1")
    class RateLimitGovernanceTest {

        /**
         * 限流触发与治理链路顺序的合并断言。
         * <p>
         * 同一测试方法内完成「第 1 次通过 → 第 2 次被限流」的完整断言，
         * 避免跨方法令牌桶状态污染。
         */
        @Test
        @DisplayName("第 1 次调用通过治理，第 2 次被限流拦截——治理在灵元之前生效")
        public void testRateLimitInterceptsBeforeLing() throws InterruptedException {
            // 等待令牌桶装满（确保第 1 次调用有令牌可用）
            Thread.sleep(1100);

            // 第 1 次调用——应当正常通过治理链
            ResponseResult<OAuthRenderResult> firstCall =
                    authController.socialRender("tenant_vip", "gitee");
            log.info("First OAuth call passed governance: code={}", firstCall.getCode());
            Assertions.assertEquals(200, firstCall.getCode(),
                    "限流配额内的第 1 次调用应当通过治理");
            Assertions.assertNotNull(firstCall.getData().getRedirectUrl(),
                    "第 1 次调用应当真实路由到灵元 provider");

            // 第 2 次调用——令牌桶已空，应当被 ResilienceGovernanceFilter 限流
            // 这证明 ResilienceGovernanceFilter (300) 在 TerminalInvokerFilter 之前拦截，
            // 被限流的请求不会到达灵元实现
            LingInvocationException ex = Assertions.assertThrows(
                    LingInvocationException.class,
                    () -> authController.socialRender("tenant_vip", "gitee"),
                    "第 2 次调用应当触发限流被拒绝");
            log.info("Second OAuth call rate-limited: kind={}, fqsid={}",
                    ex.getKind(), ex.getFqsid());
            Assertions.assertEquals(
                    LingInvocationException.ErrorKind.RATE_LIMITED, ex.getKind(),
                    "第 2 次调用应当被限流拦截，错误类型为 RATE_LIMITED");
        }
    }
}
