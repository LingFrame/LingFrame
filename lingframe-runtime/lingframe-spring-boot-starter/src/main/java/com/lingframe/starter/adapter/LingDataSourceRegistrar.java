package com.lingframe.starter.adapter;

import com.lingframe.api.storage.ManagedDataSourceRegistry;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import com.lingframe.starter.transaction.LingManagedTransactionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.UrlResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.Supplier;

/**
 * 灵元数据源自动注册器
 * <p>
 * 在灵元容器初始化时，自动为灵元注册数据源相关 Bean，避免灵元开发者手动编写样板代码。
 * </p>
 *
 * <h3>决策树（分支 A / 分支 B）</h3>
 * <ul>
 * <li><b>分支 A（独立库，模式 2）</b>：灵元配置了 {@code spring.datasource.url}，
 *     维持现状自建独立连接池（私有异构存储）；</li>
 * <li><b>分支 B（受管共享，模式 1/3）</b>：灵元未配 {@code url} 但需要数据访问时，
 *     从 {@link ManagedDataSourceRegistry} 按 {@code lingframe.ling.datasource-ref}
 *     （默认 {@code "default"}）拉取受管 {@code LingDataSourceProxy}，以单向 Singleton
 *     注入子容器并标记 {@code @Primary}（坚决不建立 Spring 父子容器）；穿透总开关
 *     {@code lingframe.tx.propagation.enabled=true} 时再注册双路径事务管理器
 *     {@code LingManagedTransactionManager}（应急降级：关闭时退回独立连接心智）。</li>
 * </ul>
 *
 * <h3>生效条件</h3>
 * <ul>
 * <li>未禁用自动配置 {@code lingframe.ling.auto-datasource=false}</li>
 * </ul>
 */
@Slf4j
public class LingDataSourceRegistrar {

    private static final String PROP_AUTO_DATASOURCE = "lingframe.ling.auto-datasource";
    private static final String PROP_DATASOURCE_URL = "spring.datasource.url";
    private static final String PROP_PROPAGATION_ENABLED = "lingframe.tx.propagation.enabled";
    private static final String PROP_DATASOURCE_REF = "lingframe.ling.datasource-ref";
    private static final String PROP_DATASOURCE_ID = "lingframe.ling.datasource-id";
    private static final String DEFAULT_SCHEMA_LOCATION = "schema.sql";
    private static final String TSM_CLASS_NAME = "org.springframework.transaction.support.TransactionSynchronizationManager";

    /**
     * 在灵元容器初始化时注册数据源相关 Bean
     *
     * @param context      灵元的 GenericApplicationContext
     * @param classLoader  灵元的 ClassLoader
     * @param lingId       灵元 ID（用于日志）
     * @param registry     受管数据源总线（灵核 starter 装配的灵核级单例；分支 B 使用）
     */
    public static void register(GenericApplicationContext context, ClassLoader classLoader, String lingId,
                                ManagedDataSourceRegistry registry) {
        Environment env = context.getEnvironment();

        // 检查是否禁用自动数据源配置（默认启用）
        boolean autoDataSourceEnabled = env.getProperty(PROP_AUTO_DATASOURCE, Boolean.class, true);
        if (!autoDataSourceEnabled) {
            log.debug("[{}] ling auto-datasource configuration is disabled", lingId);
            return;
        }

        // 分支 A：灵元自带独立 URL -> 走既有自建连接池逻辑（模式 2）
        String dataSourceUrl = env.getProperty(PROP_DATASOURCE_URL);
        if (dataSourceUrl != null && !dataSourceUrl.trim().isEmpty()) {
            registerIsolatedDataSource(context, classLoader, lingId, env, dataSourceUrl);
            // 存储灵元（模式 3 供给端）：声明 lingframe.ling.datasource-id 时，把自建数据源
            // 以该 id 注册到受管总线，供其他业务灵元共享（基础设施灵元化，只增不减）
            String supplierDataSourceId = env.getProperty(PROP_DATASOURCE_ID);
            if (supplierDataSourceId != null && !supplierDataSourceId.trim().isEmpty() && registry != null) {
                registerManagedSupplier(context, registry, lingId, supplierDataSourceId);
            }
            return;
        }

        // 分支 B：灵元未配独立 URL，从受管数据源总线拉取（模式 1 或模式 3）
        if (registry == null) {
            log.debug("[{}] ManagedDataSourceRegistry unavailable, skip managed datasource injection", lingId);
            return;
        }
        // 【配置键归入 lingframe.* 前缀】默认 "default" 与总线默认一致
        String targetDsId = env.getProperty(PROP_DATASOURCE_REF, "default");
        // TSM 共享启动期自检：穿透地基 = 灵核与灵元共享同一份 TransactionSynchronizationManager。
        // 灵元 ClassLoader 若因父委派配置错误各自加载一份 spring-tx，两栈分叉 → 穿透静默失效；
        // 这里显式比较 Class 身份，不一致输出启动期 WARN，把静默失效提升为启动期可见。
        warnIfTsmNotShared(classLoader, lingId);
        DataSource managedDataSource = registry.lookup(targetDsId);
        if (managedDataSource == null) {
            log.warn("[LingFrame] Managed datasource '{}' not found in ManagedDataSourceRegistry!", targetDsId);
            return;
        }
        log.info("[LingFrame] Injecting managed datasource (id: {}) into ling '{}'", targetDsId, lingId);
        // 以 Singleton 形式单向注册到子容器，标记为 @Primary（不建立父子容器）
        context.registerBean("dataSource", DataSource.class, () -> managedDataSource,
                bd -> bd.setPrimary(true));
        // 【穿透总开关】lingframe.tx.propagation.enabled=false 时仍注入受管数据源（业务可读写），
        // 但不注册受管事务管理器——灵元退回独立连接心智（应急降级路径）
        boolean propagationEnabled = env.getProperty(PROP_PROPAGATION_ENABLED, Boolean.class, true);
        if (propagationEnabled) {
            registerManagedTransactionManager(context, lingId, targetDsId);
        } else {
            log.warn("[LingFrame] Transaction propagation disabled (lingframe.tx.propagation.enabled=false), "
                    + "managed datasource injected without transaction manager for ling '{}'", lingId);
        }
    }

    /**
     * 分支 A：灵元自建独立连接池（模式 2）。
     */
    private static void registerIsolatedDataSource(GenericApplicationContext context, ClassLoader classLoader,
                                                   String lingId, Environment env, String dataSourceUrl) {
        log.info("[{}] Detected ling datasource configuration, registering independent DataSource", lingId);

        // 注册 DataSourceProperties
        Supplier<DataSourceProperties> propsSupplier = () -> {
            DataSourceProperties props = new DataSourceProperties();
            props.setUrl(dataSourceUrl);
            props.setUsername(env.getProperty("spring.datasource.username"));
            props.setPassword(env.getProperty("spring.datasource.password"));
            props.setDriverClassName(env.getProperty("spring.datasource.driver-class-name"));
            return props;
        };
        context.registerBean("lingDataSourceProperties", DataSourceProperties.class, propsSupplier);

        // 注册独立 DataSource（设为 Primary，覆盖可能从父容器继承的）
        Supplier<DataSource> dataSourceSupplier = () -> {
            DataSourceProperties props = context.getBean("lingDataSourceProperties", DataSourceProperties.class);
            ensureParentDirectoryExists(props.getUrl());
            return props.initializeDataSourceBuilder().build();
        };
        context.registerBean("dataSource", DataSource.class, dataSourceSupplier, bd -> bd.setPrimary(true));

        // 检查 schema.sql 是否存在（只查找灵元自身资源，不委派给父 ClassLoader）
        URL schemaUrl;
        if (classLoader instanceof URLClassLoader) {
            URLClassLoader urlClassLoader = (URLClassLoader) classLoader;
            // URLClassLoader.findResource 不委派给父 ClassLoader
            schemaUrl = urlClassLoader.findResource(DEFAULT_SCHEMA_LOCATION);
        } else {
            // 回退方案：使用 getResource（会委派）
            schemaUrl = classLoader.getResource(DEFAULT_SCHEMA_LOCATION);
        }

        if (schemaUrl != null) {
            log.info("[{}] Detected {} at {}, registering DataSourceInitializer", lingId, DEFAULT_SCHEMA_LOCATION,
                    schemaUrl);

            // 使用 UrlResource 直接引用找到的资源，避免再次通过 ClassLoader 查找
            Supplier<DataSourceInitializer> initializerSupplier = () -> {
                DataSource dataSource = context.getBean("dataSource", DataSource.class);

                ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
                populator.addScript(new UrlResource(schemaUrl));

                DataSourceInitializer initializer = new DataSourceInitializer();
                initializer.setDataSource(dataSource);
                initializer.setDatabasePopulator(populator);
                return initializer;
            };
            context.registerBean("lingDataSourceInitializer", DataSourceInitializer.class, initializerSupplier);
        } else {
            log.debug("[{}] {} not found in ling, skipping database initialization", lingId,
                    DEFAULT_SCHEMA_LOCATION);
        }
    }

    /**
     * 模式 3 供给端注册：把存储灵元自建的数据源以 dataSourceId 注册到受管总线。
     * <p>
     * 延迟取 Bean（refresh 后才可 getBean）：注册时数据源 Bean 尚未实例化；refresh 后
     * {@code getBean("dataSource")} 返回的是灵元容器内 {@code DataSourceWrapperProcessor}
     * 包装后的治理代理——【同实例】提升身份后返回，保证存储灵元自身事务管理器
     * （TSM 资源键 = 该代理实例）与总线查找命中同一对象，穿透才能成立。
     * <p>
     * 生命周期语义（基础设施只增不减）：注册后不触发 unregister，业务灵元卸载不影响。
     *
     * @param context     灵元的 GenericApplicationContext
     * @param registry    受管数据源总线（灵核级单例实例）
     * @param lingId      灵元 ID
     * @param dataSourceId 本存储灵元对外供给的数据源 ID
     */
    private static void registerManagedSupplier(GenericApplicationContext context,
                                                ManagedDataSourceRegistry registry,
                                                String lingId,
                                                String dataSourceId) {
        registry.register(dataSourceId, () -> {
            DataSource ds = context.getBean("dataSource", DataSource.class);
            if (ds instanceof LingDataSourceProxy) {
                ((LingDataSourceProxy) ds).promoteToManaged(dataSourceId);
            }
            return ds;
        });
        log.info("[{}] Registered managed datasource supplier (dataSourceId: {})", lingId, dataSourceId);
    }

    /**
     * 分支 B：注册受管模式双路径事务管理器（完整规格见 LingManagedTransactionManager 类注释）。
     * <p>
     * 仅受管共享路径使用：判根真源 = {@code getTransaction()} 时刻穿透上下文连接栈
     * （按 dataSourceId）空与否；根路径借连接 → setAutoCommit(false) → push，
     * 加入路径不 bind TSM、不碰连接。REQUIRES_NEW 物理不可达，显式降级为加入并告警。
     *
     * @param context     灵元的 GenericApplicationContext
     * @param lingId      灵元 ID
     * @param dataSourceId 受管数据源身份（与本灵元 dataSource Bean 的 id 一致）
     */
    private static void registerManagedTransactionManager(GenericApplicationContext context, String lingId,
                                                         String dataSourceId) {
        // 双路径事务管理器：灵元容器内注册为 PlatformTransactionManager Bean，
        // 事务管理器持有的受管数据源与分支 B 注入的 "dataSource" Bean 同一实例；
        // 穿透总开关关闭时本方法不被调用（灵元退回独立连接心智，无受管事务管理器）。
        // Supplier 内延迟取 dataSource Bean（refresh 时才求值，避免未 refresh 阶段 getBean）
        context.registerBean("lingTransactionManager", PlatformTransactionManager.class,
                () -> new LingManagedTransactionManager(context.getBean("dataSource", DataSource.class), dataSourceId));
        log.info("[{}] Registered managed transaction manager (dataSourceId: {})", lingId, dataSourceId);
    }

    /**
     * TSM 共享启动期自检：穿透地基 = 灵核与灵元共享同一份
     * {@code TransactionSynchronizationManager}（spring-tx 父委派）。
     * <p>
     * 用 {@code Class.forName(..., false, <ClassLoader>)} 分别以灵核与灵元 ClassLoader
     * 解析 TSM 类并比较 Class 身份——不一致（spring-tx 未按父委派注入，两栈分叉）时
     * 输出启动期 WARN：穿透不激活，受管灵元 SQL 将独立提交。
     * <p>
     * 边界：某侧 ClassLoader 解析不到 TSM（无 spring-tx 环境）时跳过检测（不误报），
     * 该场景穿透本就不可用（无 JDBC 事务根）。
     *
     * @param lingClassLoader 灵元 ClassLoader（分支 B 注入路径上可用）
     * @param lingId          灵元 ID（日志）
     */
    private static void warnIfTsmNotShared(ClassLoader lingClassLoader, String lingId) {
        ClassLoader coreClassLoader = LingDataSourceRegistrar.class.getClassLoader();
        try {
            Class<?> coreTsm = Class.forName(TSM_CLASS_NAME, false, coreClassLoader);
            Class<?> lingTsm = Class.forName(TSM_CLASS_NAME, false, lingClassLoader);
            if (coreTsm != lingTsm) {
                log.warn("[LingFrame] TransactionSynchronizationManager NOT shared between core ({}) and ling '{}' ({}), "
                                + "transaction propagation will NOT be active for managed ling; "
                                + "ensure spring-tx is parent-delegated to the core classloader",
                        coreTsm.getClassLoader(), lingId, lingTsm.getClassLoader());
            }
        } catch (ClassNotFoundException e) {
            // 无 spring-tx 环境（springdoc / 纯 Web 测试场景）：穿透本就不可用，跳过检测不误报
            log.debug("[{}] spring-tx not resolvable, skip TSM sharing self-check", lingId);
        }
    }

    private static void ensureParentDirectoryExists(String url) {
        if (url == null) {
            return;
        }
        String path = null;
        if (url.startsWith("jdbc:sqlite:")) {
            path = url.substring("jdbc:sqlite:".length());
        }
        if (path == null || path.isEmpty() || path.startsWith(":") || path.contains(":")) {
            return;
        }
        File dbFile = new File(path);
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                log.info("Created database directory: {}", parentDir.getAbsolutePath());
            } else {
                log.warn("Failed to create database directory: {}", parentDir.getAbsolutePath());
            }
        }
    }
}
