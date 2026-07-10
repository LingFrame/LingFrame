package com.lingframe.core.ling;

import com.lingframe.api.annotation.LingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link LingServiceRegistrar} 独立单测。
 * <p>
 * 覆盖：TYPE 级 @LingService 注册 / METHOD 级注册 / 隐式接口注册 /
 * implicitRegistration=false 跳过隐式 / 健壮性（无法实例化的 Bean 不崩）。
 */
@DisplayName("LingServiceRegistrar 测试")
class LingServiceRegistrarTest {

    private final DefaultLingServiceRegistry registry = mock(DefaultLingServiceRegistry.class);
    // 测试嵌套接口包名落在 com.lingframe.core.* 下，coreDefaults() 会排除它导致注册不发生。
    // clearCoreDefaults() 清空默认前缀，让测试嵌套接口被当业务接口判定。
    private final BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder()
            .clearCoreDefaults().build();

    /** 显式 METHOD 级 @LingService Bean */
    static class MethodAnnotatedBean {
        @LingService(id = "sendSms")
        public String send(String msg) {
            return msg;
        }
        @SuppressWarnings("unused")
        public void notAnnotated() {}
    }

    /** 显式 TYPE 级 @LingService Bean，实现业务接口 */
    @LingService(id = "userService")
    public static class TypeAnnotatedBean implements TestUserService {
        @Override
        public String query(String name) {
            return name;
        }
    }

    /** 隐式接口 Bean（无 @LingService，仅 implements 业务接口） */
    public static class ImplicitBean implements TestUserService {
        @Override
        public String query(String name) {
            return name;
        }
    }

    /** 无法实例化的病态 Bean（私有构造抛异常） */
    public static class NonInstantiableBean {
        private NonInstantiableBean() {
            throw new RuntimeException("Can't instantiate");
        }
        @LingService(id = "bad")
        public void test() {}
    }

    public interface TestUserService {
        String query(String name);
    }

    @Nested
    @DisplayName("显式 METHOD 级注册")
    class ExplicitMethodRegistration {

        @Test
        @DisplayName("方法上标 @LingService(id=\"sendSms\") 应按短 ID 注册 FQSID")
        void shouldRegisterMethodWithExplicitId() {
            LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, true);

            registrar.register("user-ling", new MethodAnnotatedBean(), MethodAnnotatedBean.class);

            verify(registry).registerServiceMetadata(
                    eq("user-ling:sendSms"), eq("send"), any(String[].class), eq("java.lang.String"));
            verify(registry).registerImplementationClassName("user-ling:sendSms", MethodAnnotatedBean.class.getName());
        }

        @Test
        @DisplayName("未标 @LingService 的方法不应注册")
        void shouldNotRegisterUnannotatedMethod() {
            LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, true);

            registrar.register("user-ling", new MethodAnnotatedBean(), MethodAnnotatedBean.class);

            // notAnnotated() 不应出现在任何注册调用中
            verify(registry, never()).registerServiceMetadata(
                    anyString(), eq("notAnnotated"), any(String[].class), anyString());
        }
    }

    @Nested
    @DisplayName("显式 TYPE 级注册")
    class ExplicitTypeRegistration {

        @Test
        @DisplayName("类上标 @LingService(id=\"userService\") 应把所有业务接口方法按短 ID 注册")
        void shouldRegisterTypeWithExplicitId() {
            LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, true);

            registrar.register("user-ling", new TypeAnnotatedBean(), TypeAnnotatedBean.class);

            // TYPE 级：按短 ID userService 注册
            verify(registry).registerServiceMetadata(
                    eq("user-ling:userService"), eq("query"), any(String[].class), eq("java.lang.String"));
            verify(registry).registerImplementationClassName("user-ling:userService", TypeAnnotatedBean.class.getName());
        }
    }

    @Nested
    @DisplayName("隐式接口注册")
    class ImplicitInterfaceRegistration {

        @Test
        @DisplayName("implicitRegistration=true 时无 @LingService 的 Bean 也应按接口全限定名注册")
        void shouldRegisterImplicitWhenEnabled() {
            LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, true);

            registrar.register("user-ling", new ImplicitBean(), ImplicitBean.class);

            // 隐式：按接口全限定名 com.lingframe.core.ling.LingServiceRegistrarTest$TestUserService 注册
            String expectedFqsid = "user-ling:" + TestUserService.class.getName();
            verify(registry).registerServiceMetadata(
                    eq(expectedFqsid), eq("query"), any(String[].class), eq("java.lang.String"));
        }

        @Test
        @DisplayName("implicitRegistration=false 时应跳过隐式接口注册")
        void shouldSkipImplicitWhenDisabled() {
            LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, false);

            registrar.register("user-ling", new ImplicitBean(), ImplicitBean.class);

            // 隐式注册不应发生——ImplicitBean 无 @LingService 方法，也不应按接口注册
            verify(registry, never()).registerServiceMetadata(
                    anyString(), anyString(), any(String[].class), anyString());
        }
    }

    @Nested
    @DisplayName("健壮性")
    class Robustness {

        @Test
        @DisplayName("Registrar 只反射注解不 newInstance，对无法实例化的 Bean 类应不崩")
        void shouldNotThrowOnNonInstantiableBeanClass() {
            LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, true);

            // 用 Object 充数触发反射路径——Registrar 只扫注解不实例化
            Object dummy = new Object();
            assertDoesNotThrow(() -> registrar.register("user-ling", dummy, NonInstantiableBean.class));

            // @LingService(id="bad") 方法应被注册（反射成功，实例化由调用方负责）
            verify(registry).registerServiceMetadata(
                    eq("user-ling:bad"), eq("test"), any(String[].class), eq("void"));
        }

        @Test
        @DisplayName("空参数应早返不崩")
        void shouldNotThrowOnNullArgs() {
            LingServiceRegistrar registrar = new LingServiceRegistrar(registry, filter, true);

            assertDoesNotThrow(() -> registrar.register(null, new Object(), Object.class));
            assertDoesNotThrow(() -> registrar.register("x", null, Object.class));
            assertDoesNotThrow(() -> registrar.register("x", new Object(), null));

            verifyNoInteractions(registry);
        }
    }
}
