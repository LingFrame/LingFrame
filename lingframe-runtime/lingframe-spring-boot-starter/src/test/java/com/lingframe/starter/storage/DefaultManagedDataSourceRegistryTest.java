package com.lingframe.starter.storage;

import com.lingframe.api.storage.ManagedDataSourceProvider;
import com.lingframe.api.storage.ManagedDataSourceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 受管数据源独立总线默认实现测试：register / unregister / lookup 语义与非法参数防御。
 */
@DisplayName("DefaultManagedDataSourceRegistry 受管数据源总线")
class DefaultManagedDataSourceRegistryTest {

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
    @DisplayName("注册与查找")
    class RegisterAndLookup {

        @Test
        @DisplayName("register 后 lookup 返回同一数据源实例")
        void lookupReturnsRegisteredDataSource() {
            DataSource ds = mock(DataSource.class);
            registry.register("default", () -> ds);

            assertThat(registry.lookup("default")).isSameAs(ds);
        }

        @Test
        @DisplayName("多 dataSourceId 并行注册互不干扰（模式 3 多存储灵元场景）")
        void multipleIdsAreIsolated() {
            DataSource orderDs = mock(DataSource.class);
            DataSource userDs = mock(DataSource.class);
            registry.register("order-ds", () -> orderDs);
            registry.register("user-ds", () -> userDs);

            assertThat(registry.lookup("order-ds")).isSameAs(orderDs);
            assertThat(registry.lookup("user-ds")).isSameAs(userDs);
        }

        @Test
        @DisplayName("未注册的 dataSourceId 返回 null")
        void unknownIdReturnsNull() {
            assertThat(registry.lookup("ghost")).isNull();
        }

        @Test
        @DisplayName("同 id 重复注册以最后一次为准（幂等覆盖）")
        void reRegisterOverwrites() {
            DataSource first = mock(DataSource.class);
            DataSource second = mock(DataSource.class);
            registry.register("default", () -> first);
            registry.register("default", () -> second);

            assertThat(registry.lookup("default")).isSameAs(second);
        }

        @Test
        @DisplayName("provider 显式声明 dataSourceId 与注册 key 独立（注册以 key 为准）")
        void registrationKeyWinsOverProviderId() {
            DataSource ds = mock(DataSource.class);
            registry.register("custom-key", () -> ds);

            // 查找按注册 key，而非 provider 自报的 id
            assertThat(registry.lookup("custom-key")).isSameAs(ds);
        }
    }

    @Nested
    @DisplayName("反注册")
    class Unregister {

        @Test
        @DisplayName("unregister 后 lookup 返回 null")
        void unregisterRemovesDataSource() {
            registry.register("default", providerFor("default"));
            assertThat(registry.lookup("default")).isNotNull();

            registry.unregister("default");
            assertThat(registry.lookup("default")).isNull();
        }

        @Test
        @DisplayName("unregister 不存在的 id 静默成功（幂等）")
        void unregisterUnknownIdIsNoOp() {
            registry.unregister("never-registered");
            assertThat(registry.lookup("never-registered")).isNull();
        }

        @Test
        @DisplayName("unregister 只移除目标 id，不影响其他 id")
        void unregisterKeepsOthers() {
            DataSource keep = mock(DataSource.class);
            DataSource remove = mock(DataSource.class);
            registry.register("keep", () -> keep);
            registry.register("remove", () -> remove);

            registry.unregister("remove");

            assertThat(registry.lookup("keep")).isSameAs(keep);
            assertThat(registry.lookup("remove")).isNull();
        }
    }

    @Nested
    @DisplayName("非法参数防御")
    class IllegalArguments {

        @Test
        @DisplayName("null dataSourceId 拒绝注册")
        void nullIdIsRejected() {
            assertThatThrownBy(() -> registry.register(null, providerFor("default")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("空字符串 dataSourceId 拒绝注册")
        void blankIdIsRejected() {
            assertThatThrownBy(() -> registry.register("  ", providerFor("default")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null provider 拒绝注册")
        void nullProviderIsRejected() {
            assertThatThrownBy(() -> registry.register("default", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("unregister null dataSourceId 拒绝（ConcurrentHashMap.remove(null) 会 NPE）")
        void nullIdIsRejectedOnUnregister() {
            assertThatThrownBy(() -> registry.unregister(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("dataSourceId must not be null");
        }

        @Test
        @DisplayName("lookup null 返回 null（不抛异常）")
        void lookupNullReturnsNull() {
            assertThat(registry.lookup(null)).isNull();
        }
    }

    @Nested
    @DisplayName("dataSourceId 遍历（getDataSourceIds）")
    class DataSourceIds {

        @Test
        @DisplayName("无注册时返回空集")
        void emptyWhenNothingRegistered() {
            assertThat(registry.getDataSourceIds()).isEmpty();
        }

        @Test
        @DisplayName("注册多个源后返回全部 dataSourceId")
        void returnsAllRegisteredIds() {
            registry.register("order-ds", providerFor("order-ds"));
            registry.register("user-ds", providerFor("user-ds"));

            assertThat(registry.getDataSourceIds()).containsExactlyInAnyOrder("order-ds", "user-ds");
        }

        @Test
        @DisplayName("unregister 后对应 id 从遍历集合消失")
        void unregisterRemovesFromIds() {
            registry.register("keep", providerFor("keep"));
            registry.register("remove", providerFor("remove"));

            registry.unregister("remove");

            assertThat(registry.getDataSourceIds()).containsExactly("keep");
        }

        @Test
        @DisplayName("遍历集合为不可变视图（修改抛 UnsupportedOperationException）")
        void returnedSetIsImmutable() {
            registry.register("default", providerFor("default"));

            assertThatThrownBy(() -> registry.getDataSourceIds().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
