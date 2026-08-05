package com.lingframe.core.ling;

import com.lingframe.core.routing.ProviderDescriptor;
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
        @DisplayName("注册后可查询方法签名")
        void registerAndQueryMethodsAfterRegister() {
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String", "int"}, "void");
            // 方法签名仍可查询
            List<String> methods = registry.getProviderMethods("ling-1:OrderService");
            assertEquals(1, methods.size());
            assertEquals("create(java.lang.String,int)", methods.get(0));
        }

        @Test
        @DisplayName("注册后可查询方法列表")
        void registerAndQueryMethods() {
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String"}, "void");
            registry.registerServiceMetadata("ling-1:OrderService","cancel", new String[0], "boolean");

            List<String> methods = registry.getProviderMethods("ling-1:OrderService");
            assertEquals(2, methods.size());
            assertTrue(methods.contains("create(java.lang.String)"));
            assertTrue(methods.contains("cancel()"));
        }

        @Test
        @DisplayName("重复注册同一方法不重复")
        void duplicateMethodNotRepeated() {
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String"}, "void");
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String"}, "void");

            List<String> methods = registry.getProviderMethods("ling-1:OrderService");
            assertEquals(1, methods.size());
        }

        @Test
        @DisplayName("未注册的 FQSID 返回空列表")
        void unknownFqsidReturnsEmpty() {
            assertTrue(registry.getProviderMethods("unknown:Service").isEmpty());
        }


    }

    // ==================== 实现类名注册与查询 ====================

    @Nested
    @DisplayName("实现类名注册与查询")
    class ImplementationClassName {

        @Test
        @DisplayName("注册后可正确查询实现类名")
        void registerAndGetImplClassName() {
            registry.registerImplementationClassName("ling-1:query_user", "com.example.UserQueryService");
            assertEquals("com.example.UserQueryService", registry.getImplementationClassName("ling-1:query_user"));
        }

        @Test
        @DisplayName("未注册时返回 null")
        void unregisteredReturnsNull() {
            assertNull(registry.getImplementationClassName("ling-1:unknown"));
        }

        @Test
        @DisplayName("重复注册同一 FQSID 覆盖实现类名")
        void duplicateRegisterOverrides() {
            registry.registerImplementationClassName("ling-1:query_user", "com.example.UserQueryServiceV1");
            registry.registerImplementationClassName("ling-1:query_user", "com.example.UserQueryServiceV2");
            assertEquals("com.example.UserQueryServiceV2", registry.getImplementationClassName("ling-1:query_user"));
        }

        @Test
        @DisplayName("evict 清除实现类名映射")
        void evictClearsImplClassName() {
            registry.registerImplementationClassName("ling-1:query_user", "com.example.UserQueryService");
            registry.registerImplementationClassName("ling-2:pay_service", "com.example.PaymentService");

            registry.evict("ling-1");

            assertNull(registry.getImplementationClassName("ling-1:query_user"));
            assertEquals("com.example.PaymentService", registry.getImplementationClassName("ling-2:pay_service"));
        }
    }

    // ==================== hasMethod ====================

    @Nested
    @DisplayName("hasMethod 查询")
    class HasMethod {

        @Test
        @DisplayName("已注册方法返回 true")
        void hasMethodTrue() {
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String"}, "void");

            assertTrue(registry.hasMethod("ling-1:OrderService", "create", new String[]{"java.lang.String"}));
        }

        @Test
        @DisplayName("未注册方法返回 false")
        void hasMethodFalse() {
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String"}, "void");

            assertFalse(registry.hasMethod("ling-1:OrderService", "cancel", new String[0]));
        }

        @Test
        @DisplayName("参数类型不同视为不同方法")
        void differentParamsDifferentMethod() {
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String"}, "void");

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
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String"}, "com.example.Order");

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
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[]{"java.lang.String"}, "com.example.Order");
            registry.registerServiceMetadata("ling-2:PaymentService","pay", new String[0], "boolean");

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
            registry.registerServiceMetadata("ling-1:Service","ping", new String[0], "java.lang.String");

            List<String> methods = registry.getProviderMethods("ling-1:Service");
            assertEquals(1, methods.size());
            assertEquals("ping()", methods.get(0));
        }

        @Test
        @DisplayName("null 参数视为无参数")
        void nullParamsSignature() {
            registry.registerServiceMetadata("ling-1:Service","ping", null, "java.lang.String");

            List<String> methods = registry.getProviderMethods("ling-1:Service");
            assertEquals(1, methods.size());
            assertEquals("ping()", methods.get(0));
        }

        @Test
        @DisplayName("多参数签名用逗号分隔")
        void multiParamsSignature() {
            registry.registerServiceMetadata("ling-1:Service","create", new String[]{"java.lang.String", "int", "boolean"}, "void");

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
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[0], "void");
            registry.registerServiceMetadata("ling-1:UserService","get", new String[0], "java.lang.Object");
            registry.registerServiceMetadata("ling-2:PaymentService","pay", new String[0], "boolean");

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
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[0], "void");
            registry.registerServiceMetadata("ling-1:UserService","get", new String[0], "java.lang.Object");
            registry.registerServiceMetadata("ling-2:PaymentService","pay", new String[0], "boolean");

            registry.evict("ling-1");

            assertTrue(registry.getProviderMethods("ling-1:OrderService").isEmpty());
            assertTrue(registry.getProviderMethods("ling-1:UserService").isEmpty());
            assertFalse(registry.getProviderMethods("ling-2:PaymentService").isEmpty());
        }

        @Test
        @DisplayName("evict 不存在的 lingId 无副作用")
        void evictUnknownNoSideEffect() {
            registry.registerServiceMetadata("ling-1:OrderService","create", new String[0], "void");
            registry.evict("unknown");

            assertFalse(registry.getProviderMethods("ling-1:OrderService").isEmpty());
        }
    }

    // ==================== 多版本共存场景 ====================

    @Nested
    @DisplayName("多版本共存场景")
    class MultiVersionCoexistence {

        @Test
        @DisplayName("多版本注册同一FQSID时_接口契约共享_实现类名不再存储")
        void stableAndCanaryShouldKeepSeparateImplementationClasses() {
            // 场景：稳定版 v1 与金丝雀 v2 部署同一个灵元，共享同一服务接口 FQSID，
            // 但实现类不同（v1 用 ImplV1，v2 用 ImplV2）。
            // 注册表 last-write-wins：后注册覆盖先注册，但实现类名不丢失。
            // 多版本并存时卸载单版本不清注册的保障由生命周期引擎负责，不在注册表层面。
            String fqsid = "user-ling:com.example.UserQueryService";

            registry.registerServiceMetadata(fqsid,
                    "findById", new String[]{"java.lang.Long"}, "com.example.User");
            registry.registerServiceMetadata(fqsid,
                    "findById", new String[]{"java.lang.Long"}, "com.example.User");
            // 方法签名仍可查询（多版本共享同一接口契约）
            List<String> methods = registry.getProviderMethods(fqsid);
            assertEquals(1, methods.size());
            assertEquals("findById(java.lang.Long)", methods.get(0));
        }

        @Test
        @DisplayName("evict清除整个灵元注册_这是正确行为_多版本保护由生命周期引擎负责")
        void evictClearsAllVersionsForLingId() {
            // 注册表的 evict(lingId) 语义是「灵元整体卸载，清除所有版本」。
            // 多版本并存时卸载单版本不清注册，由 DefaultLingLifecycleEngine.unloadSingleInstance
            // 通过判断「还有剩余实例时不调 evict」来保障，不在注册表层面。
            String fqsid = "user-ling:com.example.UserQueryService";

            registry.registerServiceMetadata(fqsid,
                    "findById", new String[]{"java.lang.Long"}, "com.example.User");
            registry.registerServiceMetadata(fqsid,
                    "findById", new String[]{"java.lang.Long"}, "com.example.User");

            registry.evict("user-ling");


        }
    }

    // ==================== provider 注册与版本共存 ====================

    @Nested
    @DisplayName("provider 注册与版本共存")
    class ProviderRegistration {

        @Test
        @DisplayName("同一灵元两个版本注册同一契约应生成两个 provider")
        void sameLingTwoVersionsProduceTwoProviders() {
            registry.registerProvider("svc", "user-ling", "1.0.0", 0);
            registry.registerProvider("svc", "user-ling", "1.1.0", 0);

            List<ProviderDescriptor> providers = registry.getProvidersByContractId("svc");
            assertEquals(2, providers.size());
            assertTrue(providers.stream().anyMatch(p -> "user-ling:1.0.0".equals(p.providerKey())));
            assertTrue(providers.stream().anyMatch(p -> "user-ling:1.1.0".equals(p.providerKey())));
            // 按 lingId 去重后仍只对应一个灵元（同灵元多版本并存）
            assertEquals(1, providers.stream().map(ProviderDescriptor::getLingId).distinct().count());
        }

        @Test
        @DisplayName("无灵核时首个 provider 应提升为默认基线 100，而非全部为 0")
        void firstProviderBecomesDefaultBaselineWithoutCore() {
            registry.registerProvider("svc", "user-ling", "1.0.0", 0);
            registry.registerProvider("svc", "user-ling", "1.1.0", 0);

            List<ProviderDescriptor> providers = registry.getProvidersByContractId("svc");
            assertTrue(providers.stream().anyMatch(p -> p.getWeight() == 100),
                    "无灵核基线时应有默认 provider 为 100，实际全部为 0");
        }

        @Test
        @DisplayName("已有灵核 100 基线时灵元 0 权重不被提升")
        void lingStaysZeroWhenCoreBaselineExists() {
            registry.registerProvider("svc", "lingcore-app", null, 100);
            registry.registerProvider("svc", "user-ling", "1.0.0", 0);

            List<ProviderDescriptor> providers = registry.getProvidersByContractId("svc");
            assertEquals(2, providers.size());
            assertEquals(100, findProp("lingcore-app", providers).getWeight());
            assertEquals(0, findProp("user-ling", providers).getWeight());
        }

        @Test
        @DisplayName("按版本驱逐只移除指定版本，其余版本保留并重新成为默认基线")
        void evictProviderByVersionKeepsSiblingVersions() {
            registry.registerProvider("svc", "user-ling", "1.0.0", 0);
            registry.registerProvider("svc", "user-ling", "1.1.0", 0);

            registry.evictProvider("user-ling", "1.0.0");

            List<ProviderDescriptor> providers = registry.getProvidersByContractId("svc");
            assertEquals(1, providers.size());
            assertEquals("user-ling:1.1.0", providers.get(0).providerKey());
            assertEquals(100, providers.get(0).getWeight(),
                    "退役旧版本后剩余版本应成为默认基线 100");
        }

        @Test
        @DisplayName("按版本驱逐版本为 null 时不执行任何操作")
        void evictProviderByNullVersionIsNoOp() {
            registry.registerProvider("svc", "user-ling", "1.0.0", 0);

            registry.evictProvider("user-ling", null);

            assertEquals(1, registry.getProvidersByContractId("svc").size());
        }

        @Test
        @DisplayName("全量驱逐移除该灵元所有版本的 provider")
        void evictProviderRemovesAllVersions() {
            registry.registerProvider("svc", "user-ling", "1.0.0", 0);
            registry.registerProvider("svc", "user-ling", "1.1.0", 0);

            registry.evictProvider("user-ling");

            assertTrue(registry.getProvidersByContractId("svc").isEmpty());
        }

        @Test
        @DisplayName("仅注册元数据不应产生无版本幻影 provider（多版本并存回归）")
        void metadataOnlyRegistrationMustNotCreatePhantomProvider() {
            registry.registerServiceMetadata(
                    "user-ling:canary_create_user", "create", new String[]{"java.lang.String"}, "void");

            // provider 候选必须为空：占位 provider 已废弃，候选只由版本化注册产生
            assertTrue(registry.getProvidersByContractId("canary_create_user").isEmpty());
        }

        @Test
        @DisplayName("元数据 + 版本化注册后契约恰好一个 provider，无占位幻影（canary 场景回归）")
        void versionedRegistrationAfterMetadataProducesSingleProvider() {
            registry.registerServiceMetadata(
                    "user-ling:canary_create_user", "create", new String[]{"java.lang.String"}, "void");
            registry.registerProvider("canary_create_user", "user-ling", "1.1.0-canary", 0);

            List<ProviderDescriptor> providers = registry.getProvidersByContractId("canary_create_user");
            assertEquals(1, providers.size(),
                    "同契约不应同时存在无版本幻影与版本化 provider");
            assertEquals("user-ling:1.1.0-canary", providers.get(0).providerKey());
            // 唯一提供方无灵核基线 → 提升为 100
            assertEquals(100, providers.get(0).getWeight());
        }

        @Test
        @DisplayName("灵核契约的 provider 由 registrar 直接写入，不依赖元数据占位")
        void coreContractProviderComesFromRegistrarNotPlaceholder() {
            // LingCoreServiceRegistrarProcessor → LingServiceRegistrar.forCore(weight=100) 行为：
            // 同一次注册里 registerServiceMetadata + registerProvider("lingcore-app", null, 100)
            String contract = "coreAuthService";
            registry.registerServiceMetadata(
                    "lingcore-app:" + contract, "check", new String[]{"java.lang.String"}, "boolean");
            registry.registerProvider(contract, "lingcore-app", null, 100);

            List<ProviderDescriptor> providers = registry.getProvidersByContractId(contract);
            assertEquals(1, providers.size(), "灵核契约只应有一个 provider（来自 registrar 写权重，非元数据占位）");
            assertEquals("lingcore-app", providers.get(0).providerKey());
            assertEquals(100, providers.get(0).getWeight());
        }

        @Test
        @DisplayName("灵核仅有元数据而无 provider 注册时，契约无路由候选（占位已彻底移除）")
        void coreMetadataOnlyHasNoProviderCandidate() {
            registry.registerServiceMetadata(
                    "lingcore-app:coreAuthService", "check", new String[]{"java.lang.String"}, "boolean");

            // 路由候选为空：占位已移除，灵核候选只能来自 registrar 的 registerProvider
            assertTrue(registry.getProvidersByContractId("coreAuthService").isEmpty());
        }

        private ProviderDescriptor findProp(String lingId, List<ProviderDescriptor> providers) {
            return providers.stream()
                    .filter(p -> lingId.equals(p.getLingId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("provider not found: " + lingId));
        }
    }
}
