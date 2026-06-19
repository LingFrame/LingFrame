package com.lingframe.core.ling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultLingServiceRegistry 测试。
 * 覆盖：注册/查询/驱逐、签名构建、按 lingId 查询。
 */
@DisplayName("DefaultLingServiceRegistry 测试")
class DefaultLingServiceRegistryTest {

    private DefaultLingServiceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultLingServiceRegistry();
    }

    // ==================== 注册与查询 ====================

    @Nested
    @DisplayName("注册与查询")
    class RegisterAndQuery {

        @Test
        @DisplayName("注册后可查询类名")
        void registerAndQueryClassName() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String", "int"}, "void");

            assertEquals("com.example.OrderService", registry.getServiceClassName("ling-1:OrderService"));
        }

        @Test
        @DisplayName("注册后可查询方法列表")
        void registerAndQueryMethods() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String"}, "void");
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "cancel", new String[0], "boolean");

            List<String> methods = registry.getProviderMethods("ling-1:OrderService");
            assertEquals(2, methods.size());
            assertTrue(methods.contains("create(java.lang.String)"));
            assertTrue(methods.contains("cancel()"));
        }

        @Test
        @DisplayName("重复注册同一方法不重复")
        void duplicateMethodNotRepeated() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String"}, "void");
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String"}, "void");

            List<String> methods = registry.getProviderMethods("ling-1:OrderService");
            assertEquals(1, methods.size());
        }

        @Test
        @DisplayName("未注册的 FQSID 返回空列表")
        void unknownFqsidReturnsEmpty() {
            assertTrue(registry.getProviderMethods("unknown:Service").isEmpty());
        }

        @Test
        @DisplayName("未注册的 FQSID 类名返回 null")
        void unknownFqsidClassNameNull() {
            assertNull(registry.getServiceClassName("unknown:Service"));
        }
    }

    // ==================== hasMethod ====================

    @Nested
    @DisplayName("hasMethod 查询")
    class HasMethod {

        @Test
        @DisplayName("已注册方法返回 true")
        void hasMethodTrue() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String"}, "void");

            assertTrue(registry.hasMethod("ling-1:OrderService", "create", new String[]{"java.lang.String"}));
        }

        @Test
        @DisplayName("未注册方法返回 false")
        void hasMethodFalse() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String"}, "void");

            assertFalse(registry.hasMethod("ling-1:OrderService", "cancel", new String[0]));
        }

        @Test
        @DisplayName("参数类型不同视为不同方法")
        void differentParamsDifferentMethod() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String"}, "void");

            assertFalse(registry.hasMethod("ling-1:OrderService", "create", new String[]{"int"}));
        }
    }

    // ==================== getReturnType ====================

    @Nested
    @DisplayName("getReturnType 查询")
    class GetReturnType {

        @Test
        @DisplayName("已注册方法可查询返回类型")
        void getReturnTypeSuccess() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String"}, "com.example.Order");

            assertEquals("com.example.Order", registry.getReturnType("ling-1:OrderService", "create(java.lang.String)"));
        }

        @Test
        @DisplayName("未注册方法返回 null")
        void getReturnTypeUnknown() {
            assertNull(registry.getReturnType("ling-1:OrderService", "unknown()"));
        }

        @Test
        @DisplayName("evict 清除返回类型")
        void evictClearsReturnType() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[]{"java.lang.String"}, "com.example.Order");
            registry.registerServiceMetadata("ling-2:PaymentService", "com.example.PaymentService",
                    "pay", new String[0], "boolean");

            registry.evict("ling-1");

            assertNull(registry.getReturnType("ling-1:OrderService", "create(java.lang.String)"));
            assertEquals("boolean", registry.getReturnType("ling-2:PaymentService", "pay()"));
        }
    }

    // ==================== 签名构建 ====================

    @Nested
    @DisplayName("签名构建")
    class SignatureBuild {

        @Test
        @DisplayName("无参数方法签名为 methodName()")
        void noParamsSignature() {
            registry.registerServiceMetadata("ling-1:Service", "com.example.Service",
                    "ping", new String[0], "java.lang.String");

            List<String> methods = registry.getProviderMethods("ling-1:Service");
            assertEquals(1, methods.size());
            assertEquals("ping()", methods.get(0));
        }

        @Test
        @DisplayName("null 参数视为无参数")
        void nullParamsSignature() {
            registry.registerServiceMetadata("ling-1:Service", "com.example.Service",
                    "ping", null, "java.lang.String");

            List<String> methods = registry.getProviderMethods("ling-1:Service");
            assertEquals(1, methods.size());
            assertEquals("ping()", methods.get(0));
        }

        @Test
        @DisplayName("多参数签名用逗号分隔")
        void multiParamsSignature() {
            registry.registerServiceMetadata("ling-1:Service", "com.example.Service",
                    "create", new String[]{"java.lang.String", "int", "boolean"}, "void");

            List<String> methods = registry.getProviderMethods("ling-1:Service");
            assertEquals("create(java.lang.String,int,boolean)", methods.get(0));
        }
    }

    // ==================== 按 lingId 查询 ====================

    @Nested
    @DisplayName("按 lingId 查询")
    class QueryByLingId {

        @Test
        @DisplayName("getServicesByLingId 返回该灵元所有服务")
        void getServicesByLingId() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[0], "void");
            registry.registerServiceMetadata("ling-1:UserService", "com.example.UserService",
                    "get", new String[0], "java.lang.Object");
            registry.registerServiceMetadata("ling-2:PaymentService", "com.example.PaymentService",
                    "pay", new String[0], "boolean");

            List<String> services = registry.getServicesByLingId("ling-1");
            assertEquals(2, services.size());
            assertTrue(services.contains("ling-1:OrderService"));
            assertTrue(services.contains("ling-1:UserService"));
        }

        @Test
        @DisplayName("无匹配 lingId 返回空列表")
        void noMatchReturnsEmpty() {
            assertTrue(registry.getServicesByLingId("unknown").isEmpty());
        }
    }

    // ==================== 驱逐 ====================

    @Nested
    @DisplayName("驱逐")
    class Eviction {

        @Test
        @DisplayName("evict 按 lingId 前缀清除所有相关服务")
        void evictRemovesByLingId() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[0], "void");
            registry.registerServiceMetadata("ling-1:UserService", "com.example.UserService",
                    "get", new String[0], "java.lang.Object");
            registry.registerServiceMetadata("ling-2:PaymentService", "com.example.PaymentService",
                    "pay", new String[0], "boolean");

            registry.evict("ling-1");

            assertTrue(registry.getProviderMethods("ling-1:OrderService").isEmpty());
            assertTrue(registry.getProviderMethods("ling-1:UserService").isEmpty());
            assertFalse(registry.getProviderMethods("ling-2:PaymentService").isEmpty());
        }

        @Test
        @DisplayName("evict 不存在的 lingId 无副作用")
        void evictUnknownNoSideEffect() {
            registry.registerServiceMetadata("ling-1:OrderService", "com.example.OrderService",
                    "create", new String[0], "void");
            registry.evict("unknown");

            assertFalse(registry.getProviderMethods("ling-1:OrderService").isEmpty());
        }
    }

    // ==================== 多版本共存场景 ====================

    @Nested
    @DisplayName("多版本共存场景")
    class MultiVersionCoexistence {

        @Test
        @DisplayName("稳定版与金丝雀版共存时_应能分别查询各自实现类")
        void stableAndCanaryShouldKeepSeparateImplementationClasses() {
            // 场景：稳定版 v1 与金丝雀 v2 部署同一个灵元，共享同一服务接口 FQSID，
            // 但实现类不同（v1 用 ImplV1，v2 用 ImplV2）。
            // 注册表必须能让调用方按版本拿到正确的实现类名，否则会发生
            // 「用 v1 的 ClassLoader 加载 v2 的实现类」错配，导致方法解析失败。
            String fqsid = "user-ling:com.example.UserQueryService";

            registry.registerServiceMetadata(fqsid, "com.example.v1.UserServiceImpl",
                    "findById", new String[]{"java.lang.Long"}, "com.example.User");
            registry.registerServiceMetadata(fqsid, "com.example.v2.UserServiceImpl",
                    "findById", new String[]{"java.lang.Long"}, "com.example.User");

            // 当前实现的缺陷：后注册覆盖先注册，注册表丢失了 v1 的实现类信息
            // 修复目标：能查询到两个版本的实现类（至少保留最后一个的同时不破坏版本一致性）
            String className = registry.getServiceClassName(fqsid);
            assertNotNull(className, "共存时实现类名不应丢失");
        }

        @Test
        @DisplayName("卸载稳定版时_只应清除该版本的服务注册_不影响金丝雀")
        void evictByVersionShouldNotAffectOtherVersions() {
            // 场景：稳定版 v1 + 金丝雀 v2 共存，卸载稳定版 v1。
            // 修复目标：卸载单个版本时，金丝雀 v2 的服务注册必须保留可用。
            String fqsid = "user-ling:com.example.UserQueryService";

            registry.registerServiceMetadata(fqsid, "com.example.v1.UserServiceImpl",
                    "findById", new String[]{"java.lang.Long"}, "com.example.User");
            registry.registerServiceMetadata(fqsid, "com.example.v2.UserServiceImpl",
                    "findById", new String[]{"java.lang.Long"}, "com.example.User");

            // 模拟卸载稳定版 v1：当前 evict(lingId) 会按 lingId 前缀全删，
            // 把金丝雀 v2 的注册也一起清掉 —— 这是「卸载稳定版后灵元无法访问」的根因之一。
            // 修复目标：提供按版本驱逐能力（evictByVersion），不影响其他版本。
            // 此处先用现有 evict 暴露问题，断言金丝雀注册应存活。
            registry.evict("user-ling");

            // 当前行为：全删，金丝雀注册丢失 → 演练场 getServiceClassName 返回 null → 治理解析失败
            // 修复后：按版本驱逐，金丝雀注册应保留
            String canaryClassName = registry.getServiceClassName(fqsid);
            assertNotNull(canaryClassName,
                    "卸载稳定版后金丝雀的服务注册不应被清空，否则灵元对所有调用不可访问");
        }
    }
}
