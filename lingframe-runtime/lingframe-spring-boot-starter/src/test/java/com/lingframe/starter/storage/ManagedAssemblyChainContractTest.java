package com.lingframe.starter.storage;

import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.ManagedDataSourceRegistry;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import com.lingframe.infra.storage.spring.DataSourceWrapperProcessor;
import com.lingframe.starter.configuration.LingFrameLifecycleBeansConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受管数据源装配链契约测试（回归锚点）。
 * <p>
 * 覆盖「灵核 BPP 包装 → 受管总线注册 → 灵元 getConnection 复用穿透连接」的完整装配链——
 * 任一环节断裂（如代理身份丢失、TSM 资源键失配）都会让本契约红。
 * 直接调用真实装配方法（{@code LingFrameLifecycleBeansConfiguration#managedDataSourceRegistry}），
 * 不复刻逻辑：装配实现变更会自动反映到测试结果。
 */
@DisplayName("受管数据源装配链契约")
class ManagedAssemblyChainContractTest {

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    /** 复刻灵核容器真实路径：DataSourceWrapperProcessor 包装连接池 */
    private DataSource wrapViaCoreProcessor(DataSource rawPool, PermissionService permissionService) {
        ApplicationContext appContext = mock(ApplicationContext.class);
        when(appContext.getBean(PermissionService.class)).thenReturn(permissionService);
        DataSourceWrapperProcessor bpp = new DataSourceWrapperProcessor(appContext);
        return (DataSource) bpp.postProcessAfterInitialization(rawPool, "dataSource");
    }

    @Test
    @DisplayName("真实装配：BPP 包装后的灵核代理经总线注册后，灵元 getConnection 复用穿透连接（不向底层池借新连接）")
    void coreAssemblyChainReusesPenetrationConnection() throws Exception {
        DataSource rawPool = mock(DataSource.class);
        Connection poolConn = mock(Connection.class);
        when(rawPool.getConnection()).thenReturn(poolConn);
        PermissionService permissionService = mock(PermissionService.class);
        DataSource coreBean = wrapViaCoreProcessor(rawPool, permissionService);

        // 调用真实装配方法（@Bean 即普通方法，mock 参数即可）
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(coreBean);
        LingFrameLifecycleBeansConfiguration config = new LingFrameLifecycleBeansConfiguration();
        ManagedDataSourceRegistry registry = config.managedDataSourceRegistry(provider, permissionService);

        // 模拟 TransactionPropagationFilter 已把灵核事务连接压栈
        Connection txConn = mock(Connection.class);
        when(txConn.isClosed()).thenReturn(false);
        LingTransactionContext.pushConnection("default", txConn);

        // 灵元侧注入 registry.lookup("default")：穿透生效时复用 txConn，底层池不被借出
        DataSource managed = registry.lookup("default");
        assertNotNull(managed);
        managed.getConnection();
        verify(rawPool, never()).getConnection();
    }

    @Test
    @DisplayName("总线注册的代理与灵核事务管理器持有的代理是同一实例（TSM 资源键一致性）")
    void registryReturnsSameInstanceAsCoreBean() {
        DataSource rawPool = mock(DataSource.class);
        PermissionService permissionService = mock(PermissionService.class);
        DataSource coreBean = wrapViaCoreProcessor(rawPool, permissionService);

        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(coreBean);
        LingFrameLifecycleBeansConfiguration config = new LingFrameLifecycleBeansConfiguration();
        ManagedDataSourceRegistry registry = config.managedDataSourceRegistry(provider, permissionService);

        // 同实例提升身份后返回：灵核 DataSourceTransactionManager（TSM 键=coreBean）与总线查找命中同一对象
        assertSame(coreBean, registry.lookup("default"));
    }

    @Test
    @DisplayName("灵核 0 存储：总线注册 lambda 返回 null，lookup 不到 default（分支 B 走不到注入）")
    void lookupReturnsNullWithoutCoreDataSource() {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        LingFrameLifecycleBeansConfiguration config = new LingFrameLifecycleBeansConfiguration();
        ManagedDataSourceRegistry registry = config.managedDataSourceRegistry(provider, mock(PermissionService.class));

        // 供给者 key 已注册但当前无数据源可供给：lookup 返回 null，消费端（分支 B）走不到注入
        assertThat(registry.lookup("default")).isNull();
        assertThat(registry.getDataSourceIds()).contains("default");
    }

    @Test
    @DisplayName("promoteToManaged 幂等；已具备不同身份时抛 IllegalStateException（防连接串用）")
    void promoteToManagedSemantics() {
        LingDataSourceProxy proxy = new LingDataSourceProxy(mock(DataSource.class), mock(PermissionService.class));

        proxy.promoteToManaged("default");
        proxy.promoteToManaged("default"); // 幂等 no-op

        assertThrows(IllegalStateException.class, () -> proxy.promoteToManaged("order-ds"));
    }
}
