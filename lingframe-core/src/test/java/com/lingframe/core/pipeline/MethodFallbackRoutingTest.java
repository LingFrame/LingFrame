package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.routing.ContractProviderRoutingFilter;
import com.lingframe.core.routing.ProviderDescriptor;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.RoutableTarget;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 方法级资格过滤路由测试。
 * <p>
 * 覆盖场景：老灵核/老单体全量实现契约 + 新灵元只实现部分方法。
 * 调用新灵元已实现的方法时，灵核和新灵元都在候选池，按权重决策；
 * 调用新灵元未实现的方法时，新灵元被方法级资格过滤剔除，流量 100% 落回灵核。
 * <p>
 * 这是「老单体整体作为灵元/灵核 + 新灵元只写一部分功能」场景的核心机制，
 * 路由层不引用实现方身份，只认 weight 和方法资格。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("方法级资格过滤路由测试")
class MethodFallbackRoutingTest {

    @Mock
    private LingServiceRegistry lingServiceRegistry;

    @Mock
    private LingRepository lingRepository;

    @Mock
    private LingFilterChain filterChain;

    @Mock
    private RoutableTarget coreRoutableTarget;

    @Mock
    private RoutableTarget lingRoutableTarget;

    private InvocationContext context;
    private ContractProviderRoutingFilter filter;

    private static final String CONTRACT_ID = "com.example.UserService";
    private static final String CORE_LING_ID = "lingcore-app";
    private static final String LING_LING_ID = "user-ling-v2";
    private static final String METHOD_GET = "getUser";
    private static final String METHOD_UPDATE = "updateUser";
    private static final String[] PARAMS_STRING = {"java.lang.String"};

    @BeforeEach
    void setUp() {
        filter = new ContractProviderRoutingFilter(lingServiceRegistry, lingRepository, new ProviderWeightRouter());
        context = InvocationContext.obtain();
        // 裸 contractId 作为 FQSID——入口未锁定灵元
        context.setServiceFQSID(CONTRACT_ID);
    }

    @AfterEach
    void tearDown() {
        context.recycle();
    }

    /**
     * 候选 provider 集合：灵核 weight=100 + 新灵元 weight=0。
     * 灵核全量实现，新灵元只实现 getUser。
     */
    private void stubProviders() {
        ProviderDescriptor core = new ProviderDescriptor(CONTRACT_ID, CORE_LING_ID, 100);
        ProviderDescriptor ling = new ProviderDescriptor(CONTRACT_ID, LING_LING_ID, 0);
        when(lingServiceRegistry.getProvidersByContractId(CONTRACT_ID))
                .thenReturn(Arrays.asList(core, ling));
    }

    /**
     * 灵核声明了该方法，新灵元未声明。
     */
    private void stubMethodPresence(String methodName, boolean coreHas, boolean lingHas) {
        when(lingServiceRegistry.hasMethod(CORE_LING_ID + ":" + CONTRACT_ID, methodName, PARAMS_STRING))
                .thenReturn(coreHas);
        when(lingServiceRegistry.hasMethod(LING_LING_ID + ":" + CONTRACT_ID, methodName, PARAMS_STRING))
                .thenReturn(lingHas);
    }

    @Test
    @DisplayName("调用新灵元已实现的方法：两者在池子里，按权重决策落灵核")
    void callMethodImplementedByLing_bothInPool_weightDecides() throws Throwable {
        context.setMethodName(METHOD_GET);
        context.setParameterTypeNames(PARAMS_STRING);
        stubProviders();
        // 灵核和新灵元都声明了 getUser(String)
        stubMethodPresence(METHOD_GET, true, true);
        when(lingRepository.getRoutableTarget(CORE_LING_ID)).thenReturn(coreRoutableTarget);
        when(filterChain.doFilter(context)).thenReturn(null);

        filter.doFilter(context, filterChain);

        // 默认灵核 weight=100 > 新灵元 weight=0，灵核承接全量
        assertEquals(CORE_LING_ID, context.getTargetLingId());
        assertSame(coreRoutableTarget, context.getRuntime());
        verify(filterChain).doFilter(context);
    }

    @Test
    @DisplayName("调用新灵元未实现的方法：新灵元被剔除，流量 100% 落回灵核")
    void callMethodNotImplementedByLing_lingEvicted_fallbackToCore() throws Throwable {
        context.setMethodName(METHOD_UPDATE);
        context.setParameterTypeNames(PARAMS_STRING);
        stubProviders();
        // 灵核声明了 updateUser(String)，新灵元没声明 → 被方法级资格过滤剔除
        stubMethodPresence(METHOD_UPDATE, true, false);
        when(lingRepository.getRoutableTarget(CORE_LING_ID)).thenReturn(coreRoutableTarget);
        Object expected = new Object();
        when(filterChain.doFilter(context)).thenReturn(expected);

        Object result = filter.doFilter(context, filterChain);

        assertSame(expected, result);
        // 命中灵核，不命中新灵元——方法级 fallback 成立
        assertEquals(CORE_LING_ID, context.getTargetLingId());
        assertSame(coreRoutableTarget, context.getRuntime());
        verify(filterChain).doFilter(context);
    }

    @Test
    @DisplayName("灵核未声明该方法但新灵元声明了：灵核被剔除，落新灵元")
    void callMethodOnlyImplementedByLing_coreEvicted_routeToLing() throws Throwable {
        context.setMethodName("newMethod");
        context.setParameterTypeNames(PARAMS_STRING);
        stubProviders();
        // 灵核没声明 newMethod（新灵元独有），新灵元声明了 → 灵核被剔除
        stubMethodPresence("newMethod", false, true);
        when(lingRepository.getRoutableTarget(LING_LING_ID)).thenReturn(lingRoutableTarget);
        Object expected = new Object();
        when(filterChain.doFilter(context)).thenReturn(expected);

        Object result = filter.doFilter(context, filterChain);

        assertSame(expected, result);
        assertEquals(LING_LING_ID, context.getTargetLingId());
        assertSame(lingRoutableTarget, context.getRuntime());
        verify(filterChain).doFilter(context);
    }

    @Test
    @DisplayName("两者都未声明该方法：过滤后为空，fallback 到全集兜底选第一个")
    void callMethodImplementedByNeither_fallbackToFullSet() throws Throwable {
        context.setMethodName("deprecatedMethod");
        context.setParameterTypeNames(PARAMS_STRING);
        stubProviders();
        // 两者都没声明 deprecatedMethod → qualified 为空，fallback 到全集
        stubMethodPresence("deprecatedMethod", false, false);
        // 全集第一个是灵核（weight=100），兜底选灵核
        when(lingRepository.getRoutableTarget(CORE_LING_ID)).thenReturn(coreRoutableTarget);
        when(filterChain.doFilter(context)).thenReturn(null);

        filter.doFilter(context, filterChain);

        // 兜底选全集第一个（灵核），不抛异常
        assertEquals(CORE_LING_ID, context.getTargetLingId());
        verify(filterChain).doFilter(context);
    }

    @Test
    @DisplayName("入口未提供方法签名时跳过方法级过滤，返回全集让权重路由决策")
    void noMethodSignatureSkipsFilter() throws Throwable {
        // methodName / paramTypes 为 null，无法做方法级过滤
        context.setMethodName(null);
        context.setParameterTypeNames(null);
        stubProviders();
        // 全集按 weight 决策：灵核 100 > 灵元 0
        when(lingRepository.getRoutableTarget(CORE_LING_ID)).thenReturn(coreRoutableTarget);
        when(filterChain.doFilter(context)).thenReturn(null);

        filter.doFilter(context, filterChain);

        assertEquals(CORE_LING_ID, context.getTargetLingId());
        verify(filterChain).doFilter(context);
    }

    @Test
    @DisplayName("单灵核场景：未实现该方法时过滤后为空，兜底落灵核")
    void singleCoreNotImplemented_fallbackToCoreItself() throws Throwable {
        context.setMethodName(METHOD_GET);
        context.setParameterTypeNames(PARAMS_STRING);
        // 候选只有灵核
        ProviderDescriptor core = new ProviderDescriptor(CONTRACT_ID, CORE_LING_ID, 100);
        when(lingServiceRegistry.getProvidersByContractId(CONTRACT_ID))
                .thenReturn(Collections.singletonList(core));
        // 灵核未声明 getUser（注册不全的兼容场景），过滤后为空，fallback 到全集
        when(lingServiceRegistry.hasMethod(CORE_LING_ID + ":" + CONTRACT_ID, METHOD_GET, PARAMS_STRING))
                .thenReturn(false);
        when(lingRepository.getRoutableTarget(CORE_LING_ID)).thenReturn(coreRoutableTarget);
        when(filterChain.doFilter(context)).thenReturn(null);

        filter.doFilter(context, filterChain);

        // 兜底落灵核本身，不抛异常
        assertEquals(CORE_LING_ID, context.getTargetLingId());
        verify(filterChain).doFilter(context);
    }

    @Test
    @DisplayName("Dashboard 下发权重后方法级 fallback 仍成立：未实现的方法不进池子")
    void methodFallbackWithDashboardOverride() throws Throwable {
        context.setMethodName(METHOD_UPDATE);
        context.setParameterTypeNames(PARAMS_STRING);
        stubProviders();
        stubMethodPresence(METHOD_UPDATE, true, false);
        // Dashboard 下发：灵核 0，灵元 100——但灵元未实现该方法，被剔除
        ProviderWeightRouter router = new ProviderWeightRouter();
        router.setProviderWeight(CONTRACT_ID, CORE_LING_ID, 0);
        router.setProviderWeight(CONTRACT_ID, LING_LING_ID, 100);
        filter = new ContractProviderRoutingFilter(lingServiceRegistry, lingRepository, router);
        when(lingRepository.getRoutableTarget(CORE_LING_ID)).thenReturn(coreRoutableTarget);
        Object expected = new Object();
        when(filterChain.doFilter(context)).thenReturn(expected);

        Object result = filter.doFilter(context, filterChain);

        assertSame(expected, result);
        // 即使 Dashboard 把灵元权重抬到 100、灵核降到 0，
        // 灵元未实现 updateUser → 被剔除 → 灵核兜底
        assertEquals(CORE_LING_ID, context.getTargetLingId(),
                "Dashboard 权重抬升不能让未实现方法的灵元接流量");
        verify(filterChain).doFilter(context);
    }

    @Test
    @DisplayName("选中灵核但灵核 runtime 不存在时抛路由失败")
    void selectedCoreRuntimeMissingThrowsRouteFailure() {
        context.setMethodName(METHOD_GET);
        context.setParameterTypeNames(PARAMS_STRING);
        stubProviders();
        stubMethodPresence(METHOD_GET, true, true);
        when(lingRepository.getRoutableTarget(CORE_LING_ID)).thenReturn(null);

        LingInvocationException ex = assertThrows(
                LingInvocationException.class, () -> filter.doFilter(context, filterChain));

        assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
    }
}
