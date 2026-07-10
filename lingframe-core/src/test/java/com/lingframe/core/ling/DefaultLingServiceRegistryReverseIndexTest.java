package com.lingframe.core.ling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultLingServiceRegistry#getLingIdsByContractId(String)} 独立单测。
 * <p>
 * 覆盖反向索引三种匹配模式（裸契约名 / 短 ID / 完整 FQSID 兜底）+ evict 清理。
 */
@DisplayName("DefaultLingServiceRegistry 反向索引测试")
class DefaultLingServiceRegistryReverseIndexTest {

    private DefaultLingServiceRegistry registry;

    @Nested
    @DisplayName("反向索引命中")
    class ReverseIndexHit {

        @Test
        @DisplayName("按裸契约名匹配：注册 lingId:interfaceName 后按 interfaceName 反查应命中")
        void shouldHitByInterfaceName() {
            registry = new DefaultLingServiceRegistry();

            registry.registerServiceMetadata(
                    "user-ling:com.example.UserService", "query", new String[]{"java.lang.String"}, "java.lang.String");

            List<String> lingIds = registry.getLingIdsByContractId("com.example.UserService");

            assertFalse(lingIds.isEmpty());
            assertTrue(lingIds.contains("user-ling"));
        }

        @Test
        @DisplayName("按短 ID 匹配：注册 lingId:sendSms 后按 sendSms 反查应命中")
        void shouldHitByShortId() {
            registry = new DefaultLingServiceRegistry();

            registry.registerServiceMetadata(
                    "sms-ling:sendSms", "send", new String[]{"java.lang.String"}, "void");

            List<String> lingIds = registry.getLingIdsByContractId("sendSms");

            assertFalse(lingIds.isEmpty());
            assertTrue(lingIds.contains("sms-ling"));
        }

        @Test
        @DisplayName("多灵元注册同一契约：反查应返回所有灵元")
        void shouldReturnAllLingsForSameContract() {
            registry = new DefaultLingServiceRegistry();

            registry.registerServiceMetadata(
                    "ling-a:com.example.UserService", "query", new String[]{}, "java.lang.String");
            registry.registerServiceMetadata(
                    "ling-b:com.example.UserService", "query", new String[]{}, "java.lang.String");

            List<String> lingIds = registry.getLingIdsByContractId("com.example.UserService");

            assertEquals(2, lingIds.size());
            assertTrue(lingIds.contains("ling-a"));
            assertTrue(lingIds.contains("ling-b"));
        }
    }

    @Nested
    @DisplayName("反向索引未命中")
    class ReverseIndexMiss {

        @Test
        @DisplayName("未注册的契约应返回空列表不崩")
        void shouldReturnEmptyOnUnregisteredContract() {
            registry = new DefaultLingServiceRegistry();

            List<String> lingIds = registry.getLingIdsByContractId("com.example.NonExistent");

            assertNotNull(lingIds);
            assertTrue(lingIds.isEmpty());
        }

        @Test
        @DisplayName("null 契约 ID 应返回空列表不崩")
        void shouldReturnEmptyOnNullContract() {
            registry = new DefaultLingServiceRegistry();

            List<String> lingIds = registry.getLingIdsByContractId(null);

            assertNotNull(lingIds);
            assertTrue(lingIds.isEmpty());
        }

        @Test
        @DisplayName("空契约 ID 应返回空列表不崩")
        void shouldReturnEmptyOnEmptyContract() {
            registry = new DefaultLingServiceRegistry();

            List<String> lingIds = registry.getLingIdsByContractId("");

            assertNotNull(lingIds);
            assertTrue(lingIds.isEmpty());
        }
    }

    @Nested
    @DisplayName("evict 反向索引清理")
    class EvictCleanup {

        @Test
        @DisplayName("evict 应从反向索引移除该灵元的所有契约引用")
        void shouldRemoveLingFromReverseIndexOnEvict() {
            registry = new DefaultLingServiceRegistry();

            registry.registerServiceMetadata(
                    "user-ling:com.example.UserService", "query", new String[]{}, "java.lang.String");
            registry.registerServiceMetadata(
                    "user-ling:sendSms", "send", new String[]{}, "void");

            // evict 前反查命中
            assertFalse(registry.getLingIdsByContractId("com.example.UserService").isEmpty());
            assertFalse(registry.getLingIdsByContractId("sendSms").isEmpty());

            registry.evict("user-ling");

            // evict 后反查应空
            assertTrue(registry.getLingIdsByContractId("com.example.UserService").isEmpty());
            assertTrue(registry.getLingIdsByContractId("sendSms").isEmpty());
        }

        @Test
        @DisplayName("evict 应仅移除指定灵元，不影响其他灵元")
        void shouldOnlyEvictSpecifiedLing() {
            registry = new DefaultLingServiceRegistry();

            registry.registerServiceMetadata(
                    "ling-a:com.example.UserService", "query", new String[]{}, "java.lang.String");
            registry.registerServiceMetadata(
                    "ling-b:com.example.UserService", "query", new String[]{}, "java.lang.String");

            registry.evict("ling-a");

            List<String> remaining = registry.getLingIdsByContractId("com.example.UserService");
            assertFalse(remaining.isEmpty());
            assertFalse(remaining.contains("ling-a"));
            assertTrue(remaining.contains("ling-b"));
        }
    }
}
