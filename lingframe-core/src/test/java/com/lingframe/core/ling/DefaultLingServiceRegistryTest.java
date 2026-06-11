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
}
