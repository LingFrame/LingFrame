package com.lingframe.example.saas;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.routing.ProviderDescriptor;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.example.mall.service.UserService;
import com.lingframe.api.annotation.LingReference;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SaaS 商城运行时治理可观测性集成测试。
 * <p>
 * 演示灵珑「二维路由 + 治理」的设计语义——治理随流量走，Dashboard 控制面统一调配。
 * <p>
 * <b>关键设计点</b>：灵核底座 {@code UserServiceImpl} 注册为 CORE provider（权重=100），
 * 灵元 {@code SaaSUserServiceImpl} 注册为 LING provider（权重=0）。
 * 默认流量按权重走灵核；本测试通过 {@link ProviderWeightRouter#setProviderWeight}
 * 把灵元 provider 权重调到 100、灵核 provider 权重降到 0，让流量 100% 切换到灵元，
 * 治理补丁下发到灵元后限流即触发。
 * <p>
 * <b>令牌桶状态注意</b>：{@code TokenBucketRateLimiter} 按 {@code rateLimitPerSecond=1} 每秒补充 1 个令牌。
 * 同一 {@code @SpringBootTest} 共享单例限流器，跨测试方法令牌不重置。
 * 因此限流相关断言集中在同一个测试方法内完成，避免跨方法令牌竞争；
 * 非限流断言（provider 路由权重）放独立方法，不消耗令牌。
 */
// 测试场景下 dashboard SQLite 路径指向系统临时目录，避免污染用户家目录与跨进程句柄竞争；
// 治理补丁路径与切流测试共用同一临时文件——两测试共享 Spring 上下文（LingFrameConfig 为 JVM 级静态单例，
// 不同 TestPropertySource 会触发重复 init），本测试 @AfterAll 清理补丁保证切流测试无限流干扰
@TestPropertySource(properties = {
        "lingframe.dashboard.storage.path=${java.io.tmpdir}/lingframe-mall-test-dashboard.db",
        "lingframe.governance-patch-path=${java.io.tmpdir}/lingframe-mall-test-patch.yml"
})
@SpringBootTest(classes = SaasMallApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@DisplayName("SaaS 商城运行时治理可观测性测试")
public class SaasMallGovernanceObservabilityTest {

    private static final String OAUTH_LING_ID = "saas-oauth-ling";
    private static final String USER_CONTRACT = "com.lingframe.example.mall.service.UserService";

    @LingReference
    private UserService userService;

    @Autowired
    private LocalGovernanceRegistry governanceRegistry;

    @Autowired
    private LingServiceRegistry serviceRegistry;

    @Autowired
    private ProviderWeightRouter providerWeightRouter;

    @Autowired
    private FilterRegistry filterRegistry;

    /**
     * 唯一一次性 setup：下发治理补丁 + 灰度引流配置。
     * <p>
     * 放在 {@code @BeforeAll} 而非 {@code @BeforeEach}，避免每个测试方法都重置令牌桶
     * （重置后桶会重新装满，破坏限流断言的连续性）。
     */
    @BeforeAll
    void setUpGovernance() {
        // 1. Dashboard 灰度引流：把灵元 provider 权重调到 100，流量切换到灵元
        // 路由重构后灵元 provider 键恒为 lingId:version，切流必须用版本化键
        providerWeightRouter.setProviderWeight(USER_CONTRACT, OAUTH_LING_ID + ":1.0.0", 100);
        // 灵核 provider 权重降为 0，确保流量 100% 切到灵元（否则按默认权重 50/50 分流，限流断言 flaky）
        providerWeightRouter.setProviderWeight(USER_CONTRACT, LingCoreConstants.LINGCORE_LING_ID + ":" + LingCoreConstants.LINGCORE_VERSION, 0);
        log.info("Provider weight overridden: {} ling={} weight=100, core={} weight=0",
                USER_CONTRACT, OAUTH_LING_ID, LingCoreConstants.LINGCORE_LING_ID);

        // 2. 治理补丁下发到灵元（现在灵元接流量）
        GovernancePolicy lingPatch = new GovernancePolicy();
        GovernancePolicy.InvocationPolicy invocation = new GovernancePolicy.InvocationPolicy();
        // TokenBucketRateLimiter 的 maxTokens = rateLimit，rate 必须 ≥1 才能让第 1 次调用拿到令牌
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
        providerWeightRouter.clearProviderWeight(USER_CONTRACT, OAUTH_LING_ID + ":1.0.0");
        providerWeightRouter.clearProviderWeight(USER_CONTRACT, LingCoreConstants.LINGCORE_LING_ID + ":" + LingCoreConstants.LINGCORE_VERSION);
        // 清理限流补丁：两测试共享上下文，恢复灵元治理为空策略，避免切流测试被限流干扰
        governanceRegistry.updatePatch(OAUTH_LING_ID, new GovernancePolicy());
        // 驱逐灵元弹性资源缓存（限流器/熔断器）：getLimiter 在无治理下发时会复用缓存限流器，
        // 仅清补丁不够，必须 evict 让切流测试按 config 默认值重建限流器（默认无限流）
        filterRegistry.evictLingResources(OAUTH_LING_ID);
        log.info("Governance patch cleared and resilience resources evicted for ling [{}]", OAUTH_LING_ID);
    }

    @BeforeEach
    void setUpTenantLabel() {
        // 治理测试统一用 tenant_vip，确保灵元覆盖点不拦截（tenant_block 才拦截）
        Map<String, String> labels = new HashMap<>();
        labels.put("tenant", "tenant_vip");
        LingCallContext.setLabels(labels);
    }

    @AfterEach
    void clearContext() {
        LingCallContext.clear();
    }

    @Nested
    @DisplayName("治理前置条件：Dashboard 灰度引流")
    class ProviderRoutingPreconditionTest {

        @Test
        @DisplayName("灵元 provider 权重覆盖为 100，灵核降为 0——流量 100% 切到灵元")
        public void testLingProviderWeightOverridden() {
            List<ProviderDescriptor> providers =
                    serviceRegistry.getProvidersByContractId(USER_CONTRACT);
            Assertions.assertEquals(2, providers.size(),
                    "UserService 契约应有灵核 + 灵元两个 provider");

            Integer override = providerWeightRouter.getOverrideWeight(USER_CONTRACT, OAUTH_LING_ID + ":1.0.0");
            Assertions.assertEquals(100, override,
                    "Dashboard 覆盖后灵元 provider 权重应为 100");

            Integer coreOverride = providerWeightRouter.getOverrideWeight(USER_CONTRACT, LingCoreConstants.LINGCORE_LING_ID + ":" + LingCoreConstants.LINGCORE_VERSION);
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
        @DisplayName("第 1 次调用通过治理，后续快速调用被限流拦截——治理在灵元之前生效")
        public void testRateLimitInterceptsBeforeLing() throws InterruptedException {
            // 等待令牌桶装满（确保第 1 次调用有令牌可用）
            Thread.sleep(1100);

            // 第 1 次调用——应当正常通过治理链
            String firstToken = userService.socialLogin("gitee", "rate_limit_openId_1", "nick1", "avatar1");
            log.info("First UserService call passed governance: token={}", firstToken);
            Assertions.assertNotNull(firstToken,
                    "限流配额内的第 1 次调用应当通过治理并返回 token");

            // 令牌桶容量 = rateLimit = 1，补充周期 1s。第 1 次调用已耗尽令牌，
            // 桶可能在下次调用前回补 1 个令牌（环境/插桩下单次调用耗时偶发接近 1s）。
            // 因此连续快速发起若干次调用，断言其中至少一次被 RATE_LIMITED 拒绝——
            // 这验证 ResilienceGovernanceFilter (300) 在 TerminalInvokerFilter 之前拦截，
            // 被限流的请求不会到达灵元实现，且不受单次调用耗时触发的回补时序影响。
            boolean sawRateLimited = false;
            for (int i = 2; i <= 6 && !sawRateLimited; i++) {
                final int callNo = i;
                try {
                    userService.socialLogin("gitee", "rate_limit_openId_" + i, "nick" + i, "avatar" + i);
                    log.info("Call #{} passed (token likely refilled), continuing", callNo);
                } catch (LingInvocationException ex) {
                    Assertions.assertEquals(
                            LingInvocationException.ErrorKind.RATE_LIMITED, ex.getKind(),
                            "被拦截的调用错误类型应为 RATE_LIMITED");
                    log.info("Call #{} rate-limited: kind={}, fqsid={}", callNo, ex.getKind(), ex.getFqsid());
                    sawRateLimited = true;
                }
            }
            Assertions.assertTrue(sawRateLimited,
                    "第 1 次调用后连续快速调用中应当至少出现一次限流拒绝（令牌桶容量=1）");
        }
    }
}
