package com.lingframe.core.ling;

import com.lingframe.api.annotation.LingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * implicitRegistration=false 行为测试。
 * <p>
 * 验证关闭隐式接口注册后：
 * <ul>
 *   <li>仅显式 @LingService 标注的方法/类型会注册</li>
 *   <li>灵元 Bean 实现的业务接口不会被自动注册</li>
 *   <li>provider 索引中不含隐式接口契约</li>
 * </ul>
 */
@DisplayName("implicitRegistration=false 行为测试")
class ImplicitRegistrationDisabledTest {

    /** 灵元 Bean：有显式 @LingService 方法 + 实现业务接口 */
    public static class MixedBean implements TestBusinessService {
        @LingService(id = "explicitSvc")
        public String explicit() {
            return "explicit";
        }
        @Override
        public String execute() {
            return "implicit";
        }
    }

    public interface TestBusinessService {
        String execute();
    }

    @Test
    @DisplayName("implicitRegistration=false：仅显式 @LingService 注册，隐式接口跳过")
    void shouldOnlyRegisterExplicitWhenImplicitDisabled() {
        DefaultLingServiceRegistry registry = new DefaultLingServiceRegistry();
        // 测试嵌套接口包名落在 com.lingframe.core.* 下，coreDefaults() 会排除它。
        // clearCoreDefaults() 清空默认前缀，让 TestBusinessService 被当业务接口判定。
        BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder().clearCoreDefaults().build();
        LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, false);

        registrar.register("user-ling", new MixedBean(), MixedBean.class);

        // 显式应注册为 provider
        assertFalse(registry.getProvidersByContractId("explicitSvc").isEmpty());
        assertTrue(registry.getProvidersByContractId("explicitSvc").stream()
                .anyMatch(p -> "user-ling".equals(p.getLingId())));

        // 隐式不应注册——按接口全限定名反查 provider 应空
        assertTrue(registry.getProvidersByContractId(TestBusinessService.class.getName()).isEmpty());
    }

    @Test
    @DisplayName("implicitRegistration=true：显式 + 隐式都注册（对照基线）")
    void shouldRegisterBothWhenImplicitEnabled() {
        DefaultLingServiceRegistry registry = new DefaultLingServiceRegistry();
        BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder().clearCoreDefaults().build();
        LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, true);

        registrar.register("user-ling", new MixedBean(), MixedBean.class);

        // 显式应注册
        assertFalse(registry.getProvidersByContractId("explicitSvc").isEmpty());
        // 隐式也应注册
        assertFalse(registry.getProvidersByContractId(TestBusinessService.class.getName()).isEmpty());
    }
}
