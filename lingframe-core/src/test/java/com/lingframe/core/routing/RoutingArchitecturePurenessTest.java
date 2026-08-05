package com.lingframe.core.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 路由层契约测试：路由只认 weight 与 providerKey，不引用实现方身份。
 * <p>
 * 替换旧版「RoutableTarget 无 canary 方法」的空洞护身符断言——
 * 该断言只检查方法名拼写，无法约束路由真实语义。
 * 本测试断言可观测的路由契约行为：providerKey 的迁移/迭代身份形态
 * 与 weight 的 0-100 收敛。
 */
@DisplayName("路由架构纯洁性（去身份化契约）测试")
class RoutingArchitecturePurenessTest {

    @Nested
    @DisplayName("ProviderDescriptor 身份键形态")
    class ProviderKeyShapes {

        @Test
        @DisplayName("无版本（仅灵核）时 providerKey 为裸 lingId")
        void shouldUseBareLingIdWhenNoVersion() {
            ProviderDescriptor desc = new ProviderDescriptor("svc-a", "user-ling", 70);
            assertEquals("user-ling", desc.providerKey());
            assertEquals("user-ling", desc.getLingId());
        }

        @Test
        @DisplayName("带版本时 providerKey 为 lingId:version")
        void shouldUseVersionedKeyDuringIteration() {
            ProviderDescriptor desc = new ProviderDescriptor("svc-a", "user-ling", "1.1.0", 40);
            assertEquals("user-ling:1.1.0", desc.providerKey());
            assertEquals("1.1.0", desc.getVersion());
        }

        @Test
        @DisplayName("sameId 不同版本可被路由层区分（N 元共存）")
        void shouldDistinguishIterationsByVersion() {
            ProviderDescriptor v1 = new ProviderDescriptor("svc-a", "user-ling", "1.0.0", 30);
            ProviderDescriptor v2 = new ProviderDescriptor("svc-a", "user-ling", "1.1.0", 70);
            assertEquals(2, java.util.stream.Stream.of(v1, v2)
                    .map(ProviderDescriptor::providerKey)
                    .distinct()
                    .count());
        }
    }

    @Nested
    @DisplayName("ProviderDescriptor 权重收敛")
    class WeightClamping {

        @Test
        @DisplayName("权重超过 100 收敛到 100")
        void shouldClampWeightAbove100() {
            ProviderDescriptor desc = new ProviderDescriptor("svc-a", "user-ling", 220);
            assertEquals(100, desc.getWeight());
        }

        @Test
        @DisplayName("权重低于 0 收敛到 0")
        void shouldClampWeightBelow0() {
            ProviderDescriptor desc = new ProviderDescriptor("svc-a", "user-ling", -30);
            assertEquals(0, desc.getWeight());
        }

        @Test
        @DisplayName("withWeight 生成不可变副本，原描述符权重不变")
        void shouldKeepDescriptorImmutableOnWithWeight() {
            ProviderDescriptor original = new ProviderDescriptor("svc-a", "user-ling", 70);
            ProviderDescriptor updated = original.withWeight(30);
            assertEquals(30, updated.getWeight());
            assertEquals(70, original.getWeight());
            assertEquals("user-ling", original.providerKey());
        }
    }
}
