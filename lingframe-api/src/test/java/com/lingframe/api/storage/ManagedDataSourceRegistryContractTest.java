package com.lingframe.api.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受管数据源独立总线接口契约测试。
 * <p>
 * 用最小 stub 实现锁定 {@link ManagedDataSourceRegistry} 的接口文档承诺：
 * register/lookup/unregister/getDataSourceIds 的身份引渡语义、未注册返回 null、
 * 集合视图不可变——实现类（如 runtime starter 的 DefaultManagedDataSourceRegistry）
 * 必须满足本契约，防止「注册 key 与自报 id 混淆」「遍历集合可变」等实现漂移。
 */
@DisplayName("ManagedDataSourceRegistry 受管数据源总线契约")
class ManagedDataSourceRegistryContractTest {

    /** 最小内存实现：仅体现接口文档承诺的契约语义，不含任何额外行为。 */
    private static final class StubRegistry implements ManagedDataSourceRegistry {
        private final Map<String, ManagedDataSourceProvider> providers = new LinkedHashMap<>();

        @Override
        public void register(String dataSourceId, ManagedDataSourceProvider provider) {
            providers.put(dataSourceId, provider);
        }

        @Override
        public void unregister(String dataSourceId) {
            providers.remove(dataSourceId);
        }

        @Override
        public DataSource lookup(String dataSourceId) {
            ManagedDataSourceProvider p = providers.get(dataSourceId);
            return p == null ? null : p.getDataSource();
        }

        @Override
        public Set<String> getDataSourceIds() {
            return Collections.unmodifiableSet(providers.keySet());
        }
    }

    @Nested
    @DisplayName("注册与查找契约")
    class RegisterAndLookupContract {

        @Test
        @DisplayName("register 后 lookup 按注册 key 返回同一数据源实例")
        void lookupReturnsRegisteredInstance() {
            ManagedDataSourceRegistry registry = new StubRegistry();
            DataSource ds = new FakeDataSource();
            registry.register("default", () -> ds);

            assertSame(ds, registry.lookup("default"));
        }

        @Test
        @DisplayName("未注册的 dataSourceId 返回 null")
        void unknownIdReturnsNull() {
            ManagedDataSourceRegistry registry = new StubRegistry();

            assertNull(registry.lookup("ghost"));
        }

        @Test
        @DisplayName("注册 key 与 provider 自报 id 独立（查找以注册 key 为准）")
        void registrationKeyIsAuthority() {
            ManagedDataSourceRegistry registry = new StubRegistry();
            DataSource ds = new FakeDataSource();
            // provider 自报 id 为 "self-id"，但注册 key 是 "custom-key"
            registry.register("custom-key", new ManagedDataSourceProvider() {
                @Override
                public DataSource getDataSource() {
                    return ds;
                }

                @Override
                public String getDataSourceId() {
                    return "self-id";
                }
            });

            assertSame(ds, registry.lookup("custom-key"));
            assertNull(registry.lookup("self-id"));
        }
    }

    @Nested
    @DisplayName("反注册契约")
    class UnregisterContract {

        @Test
        @DisplayName("unregister 后 lookup 返回 null 且不影响其他 id")
        void unregisterRemovesOnlyTarget() {
            ManagedDataSourceRegistry registry = new StubRegistry();
            DataSource keep = new FakeDataSource();
            DataSource remove = new FakeDataSource();
            registry.register("keep", () -> keep);
            registry.register("remove", () -> remove);

            registry.unregister("remove");

            assertSame(keep, registry.lookup("keep"));
            assertNull(registry.lookup("remove"));
        }

        @Test
        @DisplayName("unregister 未注册 id 静默成功（幂等）")
        void unregisterUnknownIsNoOp() {
            ManagedDataSourceRegistry registry = new StubRegistry();

            registry.unregister("never-registered");

            assertFalse(registry.getDataSourceIds().contains("never-registered"));
        }
    }

    @Nested
    @DisplayName("dataSourceId 遍历契约")
    class DataSourceIdsContract {

        @Test
        @DisplayName("无注册时返回空集且不为 null")
        void emptySetWhenNothingRegistered() {
            ManagedDataSourceRegistry registry = new StubRegistry();

            assertNotNull(registry.getDataSourceIds());
            assertTrue(registry.getDataSourceIds().isEmpty());
        }

        @Test
        @DisplayName("返回全部已注册 id，unregister 后同步消失")
        void idsReflectRegistrationState() {
            ManagedDataSourceRegistry registry = new StubRegistry();
            registry.register("order-ds", () -> new FakeDataSource());
            registry.register("user-ds", () -> new FakeDataSource());

            assertEquals(2, registry.getDataSourceIds().size());
            assertTrue(registry.getDataSourceIds().containsAll(
                    new HashSet<>(Arrays.asList("order-ds", "user-ds"))));

            registry.unregister("order-ds");

            assertFalse(registry.getDataSourceIds().contains("order-ds"));
            assertTrue(registry.getDataSourceIds().contains("user-ds"));
        }

        @Test
        @DisplayName("集合视图不可变（修改抛 UnsupportedOperationException）")
        void returnedSetIsImmutable() {
            ManagedDataSourceRegistry registry = new StubRegistry();
            registry.register("default", () -> new FakeDataSource());

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> registry.getDataSourceIds().clear());
        }
    }

    /** 轻量 DataSource 替身（api 模块无 Mockito，实现空方法即可满足引用语义）。 */
    private static final class FakeDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            return null;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return null;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
