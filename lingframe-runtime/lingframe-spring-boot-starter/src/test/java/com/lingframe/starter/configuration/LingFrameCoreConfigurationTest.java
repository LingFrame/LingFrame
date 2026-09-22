package com.lingframe.starter.configuration;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.runtime.SwitchableRuntimeMode;
import com.lingframe.starter.config.LingFrameProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.core.routing.LabelMatchRouter;
import com.lingframe.core.routing.ContractProviderRoutingFilter;
import com.lingframe.core.routing.InstanceRoutingFilter;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("LingFrameCoreConfiguration 测试")
class LingFrameCoreConfigurationTest {

    @org.junit.jupiter.api.AfterEach
    void clearStaticConfiguration() {
        // 本类也直接调用配置工厂方法，不经过 Spring 销毁回调，必须自行释放静态配置。
        LingFrameConfig.clear();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomRoutingConfiguration {
        @org.springframework.context.annotation.Bean
        TrafficRouter trafficRouter() {
            TrafficRouter router = mock(TrafficRouter.class);
            when(router.route(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> ((java.util.List<?>) invocation.getArgument(0)).get(0));
            return router;
        }
    }

    @Test
    @DisplayName("多个用户路由器没有唯一选择时启动明确失败")
    void ambiguousRoutersFailStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LingFrameCoreConfiguration.class))
                .withPropertyValues("lingframe.dev-mode=true")
                .withBean("firstRouter", TrafficRouter.class, LabelMatchRouter::new)
                .withBean("otherRouter", TrafficRouter.class, LabelMatchRouter::new)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).hasFailed();
                    org.assertj.core.api.Assertions.assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class);
                });
    }

    @Test
    @DisplayName("无用户路由器时装配唯一默认路由器")
    void defaultRouterIsAvailable() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LingFrameCoreConfiguration.class))
                .withPropertyValues("lingframe.dev-mode=true")
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).hasNotFailed()
                            .hasSingleBean(TrafficRouter.class);
                    org.assertj.core.api.Assertions.assertThat(context.getBean(TrafficRouter.class))
                            .isInstanceOf(LabelMatchRouter.class);
                });
    }

    @Test
    @DisplayName("用户路由器无需开启覆盖且真实过滤器装配的两个入口共用该实例")
    void customRouterIsUsedByBothRoutingEntries() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LingFrameCoreConfiguration.class))
                .withPropertyValues("lingframe.dev-mode=true")
                .withAllowBeanDefinitionOverriding(false)
                .withUserConfiguration(CustomRoutingConfiguration.class)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).hasNotFailed()
                            .hasSingleBean(TrafficRouter.class);
                    TrafficRouter router = context.getBean(TrafficRouter.class);
                    LingInstance instance = mock(LingInstance.class);
                    when(instance.getLingId()).thenReturn("route-test");
                    when(instance.getVersion()).thenReturn("v2");
                    LingRuntime runtime = mock(LingRuntime.class);
                    when(runtime.getLingId()).thenReturn("route-test");
                    when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(instance));
                    context.getBean(LingRepository.class).register(runtime);
                    FilterRegistry registry = context.getBean(FilterRegistry.class);
                    com.lingframe.core.spi.LingInvocationFilter provider = registry.getOrderedFilters().stream()
                            .filter(filter -> filter instanceof ContractProviderRoutingFilter).findFirst().get();
                    com.lingframe.core.spi.LingInvocationFilter instances = registry.getOrderedFilters().stream()
                            .filter(filter -> filter instanceof InstanceRoutingFilter).findFirst().get();
                    for (String service : new String[]{"route-test:execute", "execute"}) {
                        InvocationContext invocation = InvocationContext.obtain();
                        invocation.setServiceFQSID(service);
                        invocation.setTargetLingId("route-test");
                        invocation.setTargetVersion("v2");
                        try {
                            Object selected = provider.doFilter(invocation, current ->
                                    instances.doFilter(current, resolved -> resolved.routing().getTargetInstance()));
                            org.junit.jupiter.api.Assertions.assertSame(instance, selected);
                        } catch (Throwable failure) {
                            throw new AssertionError(failure);
                        } finally {
                            invocation.recycle();
                        }
                    }
                    org.mockito.Mockito.verify(router, org.mockito.Mockito.times(2))
                            .route(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
                    context.getBean(LingRepository.class).unregister("route-test");
                });
    }

    @Test
    @DisplayName("governancePermissionRestoreListener 应在启动时恢复持久化权限")
    void governancePermissionRestoreListenerShouldRestorePersistedPermissionsOnStartup() {
        LocalGovernanceRegistry registry = mock(LocalGovernanceRegistry.class);
        PermissionService permissionService = mock(PermissionService.class);
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Collections.singletonList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.STORAGE_SQL)
                                .accessType("WRITE")
                                .build()))
                .build();

        when(registry.getAllPatches()).thenReturn(Collections.singletonMap("demo-ling", policy));

        ApplicationListener<ApplicationReadyEvent> listener = new LingFrameCoreConfiguration()
                .governancePermissionRestoreListener(registry, permissionService);
        listener.onApplicationEvent(null);

        // syncPolicy 改用 replacePermissions 原子替换，不再先 removeLing 再 grant
        Map<String, AccessType> expected = Collections.singletonMap(Capabilities.STORAGE_SQL, AccessType.WRITE);
        verify(permissionService).replacePermissions("demo-ling", expected);
        verifyNoMoreInteractions(permissionService);
    }

    @Test
    @DisplayName("lingFrameConfig 将 abandonedJoinTimeout 配置映射到 LingRuntimeConfig.abandonedJoinTimeoutMs")
    void lingFrameConfigShouldMapAbandonedJoinTimeout() {
        LingFrameProperties properties = new LingFrameProperties();
        // 显式覆盖默认 2s：验证装配映射真实生效（而非恒为默认值）
        properties.getRuntime().setAbandonedJoinTimeout(Duration.ofMillis(500));
        LingFrameConfig config = new LingFrameCoreConfiguration()
                .lingFrameConfig(properties, new SwitchableRuntimeMode(false, null));

        assertEquals(500, config.getRuntimeConfig().getAbandonedJoinTimeoutMs());
        assertTrue(properties.getRuntime().getAbandonedJoinTimeout() != null);
    }

    @Test
    @DisplayName("lingFrameConfig 应映射弹性治理总开关与组件开关")
    void lingFrameConfigShouldMapResilienceSwitches() {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getRuntime().setResilienceEnabled(false);
        properties.getRuntime().setCircuitBreakerEnabled(false);
        properties.getRuntime().setRateLimiterEnabled(false);
        properties.getRuntime().setBulkheadEnabled(false);
        properties.getRuntime().setTimeoutEnabled(false);

        LingFrameConfig config = new LingFrameCoreConfiguration()
                .lingFrameConfig(properties, new SwitchableRuntimeMode(false, null));

        assertFalse(config.getRuntimeConfig().isResilienceEnabled());
        assertFalse(config.getRuntimeConfig().isCircuitBreakerEnabled());
        assertFalse(config.getRuntimeConfig().isRateLimiterEnabled());
        assertFalse(config.getRuntimeConfig().isBulkheadEnabled());
        assertFalse(config.getRuntimeConfig().isTimeoutEnabled());
    }
}
