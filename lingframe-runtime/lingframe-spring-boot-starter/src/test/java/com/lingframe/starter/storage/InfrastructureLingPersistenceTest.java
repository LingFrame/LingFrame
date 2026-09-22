package com.lingframe.starter.storage;

import com.lingframe.api.storage.ManagedDataSourceProvider;
import com.lingframe.api.storage.ManagedDataSourceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.ref.WeakReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 基础设施灵元（模式 3）只增不减语义测试：承载型存储灵元注册后常驻，
 * 总线强引用持有是设计意图（宁可放着不用，也不冒卸载级联风险），不构成泄漏；
 * unregister 保留为运维停用/未来能力的显式 API，基础设施路径不自动触发。
 */
@DisplayName("基础设施灵元（模式 3）只增不减语义")
class InfrastructureLingPersistenceTest {

    private final ManagedDataSourceRegistry registry = new DefaultManagedDataSourceRegistry();

    private static ManagedDataSourceProvider providerFor(String dataSourceId) {
        DataSource ds = mock(DataSource.class);
        return new ManagedDataSourceProvider() {
            @Override
            public DataSource getDataSource() {
                return ds;
            }

            @Override
            public String getDataSourceId() {
                return dataSourceId;
            }
        };
    }

    @Nested
    @DisplayName("常驻：注册后无自动清理")
    class Persistence {

        @Test
        @DisplayName("基础设施数据源注册后常驻：不调用 unregister 则持续可查（只增不减）")
        void infraDataSourcePersistsAfterRegistration() {
            registry.register("infra-order-ds", providerFor("infra-order-ds"));

            // 多次查询持续可见——无任何基于生命周期/引用的自动清理逻辑
            assertThat(registry.lookup("infra-order-ds")).isNotNull();
            assertThat(registry.lookup("infra-order-ds")).isNotNull();
            // 遍历集合始终包含（即使无消费者也不被移除）
            assertThat(registry.getDataSourceIds()).contains("infra-order-ds");
        }

        @Test
        @DisplayName("业务灵元侧活动不影响基础设施数据源常驻（无 per-ling 清理钩子）")
        void infraRegistrationSurvivesBusinessActivity() {
            registry.register("infra-user-ds", providerFor("infra-user-ds"));

            // 模拟业务灵元反复挂载/卸载：总线不因业务侧生命周期事件清理基础设施注册
            for (int i = 0; i < 5; i++) {
                registry.lookup("infra-user-ds");
                registry.unregister("business-temp-" + i);
            }

            assertThat(registry.lookup("infra-user-ds")).isNotNull();
            assertThat(registry.getDataSourceIds()).contains("infra-user-ds");
        }
    }

    @Nested
    @DisplayName("强引用持有：常驻是设计意图而非泄漏")
    class StrongReferenceHolding {

        @Test
        @DisplayName("总线强引用持有 provider：调用方释放引用后仍不被回收（常驻语义，非泄漏）")
        void registryStrongReferenceKeepsProviderResident() throws Exception {
            ManagedDataSourceProvider provider = providerFor("infra-order-ds");
            registry.register("infra-order-ds", provider);

            // 调用方释放自己的强引用，仅总线持有
            WeakReference<ManagedDataSourceProvider> weak = new WeakReference<>(provider);
            provider = null;

            // 多次 GC：总线强引用仍在 → provider 不被回收（常驻，这是「宁可放着不用」的设计意图）
            for (int i = 0; i < 5; i++) {
                System.gc();
                System.runFinalization();
                Thread.sleep(20);
            }
            assertThat(weak.get()).isNotNull();

            // 且查找仍返回同一实例（总线持有不依赖调用方生命周期）
            assertThat(registry.lookup("infra-order-ds")).isNotNull();
        }
    }

    @Nested
    @DisplayName("卸载入口：显式运维动作，非自动触发")
    class UnregisterExplicitness {

        @Test
        @DisplayName("基础设施路径不触发 unregister：注册后持续保留直至显式调用")
        void noAutomaticUnregisterForInfra() {
            registry.register("infra-log-ds", providerFor("infra-log-ds"));

            // 业务灵元卸载走既有回收通道，但总线上的基础设施注册不被自动反注册
            assertThat(registry.lookup("infra-log-ds")).isNotNull();
            assertThat(registry.getDataSourceIds()).contains("infra-log-ds");
        }

        @Test
        @DisplayName("unregister 保留为运维停用 API：显式调用后消失（未来能力入口）")
        void explicitUnregisterRemovesEntry() {
            registry.register("infra-retire-ds", providerFor("infra-retire-ds"));
            assertThat(registry.lookup("infra-retire-ds")).isNotNull();

            // 运维显式停用：反注册后不再可查
            registry.unregister("infra-retire-ds");
            assertThat(registry.lookup("infra-retire-ds")).isNull();
        }
    }
}
