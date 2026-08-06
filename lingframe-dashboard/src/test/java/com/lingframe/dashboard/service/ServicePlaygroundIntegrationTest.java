package com.lingframe.dashboard.service;

import com.lingframe.api.annotation.LingService;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.ling.BusinessInterfaceFilter;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.DefaultLingServiceRegistry;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingServiceRegistrar;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.event.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 演练场服务注册防回归集成测试。
 * <p>
 * 防回归点：本次重构曾把 LingServiceRegistrar 改走 registry.registerServiceMetadata，
 * 丢了 DefaultLingContext.registerProtocolService 的实例绑定
 * （instance.registerServiceMethod），导致演练场 hasServiceMethod 全返回 false
 * → getVersions 空 → filter 全滤掉 → 返回空列表。
 * <p>
 * 本测试用 mock(LingInstance) 验 Registrar 走 ctx.registerProtocolService 后
 * instance.registerServiceMethod 真被调——这是演练场能拿到服务的实例绑定前提。
 */
@DisplayName("演练场服务注册防回归集成测试")
class ServicePlaygroundIntegrationTest {

    public interface UserService {
        String query(String name);
    }

    public static class UserLingBean implements UserService {
        @LingService(id = "sendSms")
        public String send(String msg) {
            return msg;
        }
        @Override
        public String query(String name) {
            return "result:" + name;
        }
    }

    @Test
    @DisplayName("Registrar 走 ctx.registerProtocolService 应触发 instance.registerServiceMethod（实例绑定）")
    void shouldTriggerInstanceBindingViaRegistrar() {
        LingServiceRegistry registry = new DefaultLingServiceRegistry();
        DefaultLingRepository repository = new DefaultLingRepository();
        EventBus eventBus = new EventBus();
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus, LingFrameConfig.builder().build());

        FilterRegistry filterRegistry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(new InvokableMethodCache())
                .permissionService(permissionService)
                .lingRepository(repository)
                .trafficRouter(new LatestVersionPolicy())
                .eventBus(eventBus)
                .build());
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(filterRegistry);

        // mock(LingInstance)——registerServiceMethod 可被 verify
        LingInstance mockInstance = mock(LingInstance.class);
        when(mockInstance.getLingId()).thenReturn("user-ling");

        // 灵元级构造：注入 mockInstance，registerProtocolService 内部会调 instance.registerServiceMethod
        DefaultLingContext context = new DefaultLingContext(
                mockInstance, repository, registry, pipelineEngine, permissionService, eventBus);

        BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder()
                .clearCoreDefaults()
                .build();
        LingServiceRegistrar registrar = new LingServiceRegistrar(
                registry, filter, true, context);

        UserLingBean bean = new UserLingBean();
        registrar.register("user-ling", bean, UserLingBean.class);

        // 关键断言：instance.registerServiceMethod 被真调过——这是演练场能拿到服务的实例绑定前提。
        // 若回归（Registrar 走 registry 直接调丢了 ctx.registerProtocolService），此处 verify 失败。
        verify(mockInstance, atLeastOnce()).registerServiceMethod(
                eq("user-ling:sendSms"), eq("send"), any(String[].class));
        verify(mockInstance, atLeastOnce()).registerServiceMethod(
                eq("user-ling:" + UserService.class.getName()), eq("query"), any(String[].class));
    }

    @Test
    @DisplayName("灵核级构造（instance=null）时 Registrar 走兜底路径不调实例绑定，但 metadataCache 仍写")
    void shouldNotTriggerInstanceBindingWhenCoreContext() {
        LingServiceRegistry registry = new DefaultLingServiceRegistry();
        DefaultLingRepository repository = new DefaultLingRepository();
        EventBus eventBus = new EventBus();
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus, LingFrameConfig.builder().build());

        FilterRegistry filterRegistry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(new InvokableMethodCache())
                .permissionService(permissionService)
                .lingRepository(repository)
                .trafficRouter(new LatestVersionPolicy())
                .eventBus(eventBus)
                .build());
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(filterRegistry);

        // 灵核级构造：instance=null，registerProtocolService 内部跳过 instance.registerServiceMethod
        DefaultLingContext context = new DefaultLingContext(
                "core-app", repository, registry, pipelineEngine, permissionService, eventBus);

        BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder()
                .clearCoreDefaults()
                .build();
        LingServiceRegistrar registrar = new LingServiceRegistrar(
                registry, filter, true, context);

        UserLingBean bean = new UserLingBean();
        registrar.register("user-ling", bean, UserLingBean.class);

        // 显式 sendSms 的 FQSID 应出现在 registry（metadataCache + implClassName 仍写）
        assertTrue(registry.getServicesByLingId("user-ling").stream()
                .anyMatch(fqsid -> fqsid.contains("sendSms")),
                "灵核级上下文也应写 metadataCache");
        assertTrue(registry.getServicesByLingId("user-ling").stream()
                .anyMatch(fqsid -> fqsid.contains(UserService.class.getName())),
                "隐式接口服务也应写 metadataCache");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
