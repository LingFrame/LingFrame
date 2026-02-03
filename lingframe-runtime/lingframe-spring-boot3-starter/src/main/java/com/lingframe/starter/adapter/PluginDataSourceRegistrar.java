package com.lingframe.starter.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.UrlResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.Supplier;

/**
 * 插件数据源自动注册器
 * <p>
 * 在插件容器初始化时，自动为插件注册独立的 DataSource 和 DataSourceInitializer，
 * 避免插件开发者手动编写样板代码。
 * </p>
 *
 * <h3>生效条件</h3>
 * <ul>
 * <li>插件配置了 {@code spring.datasource.url}</li>
 * <li>未禁用自动配置 {@code lingframe.plugin.auto-datasource=false}</li>
 * </ul>
 *
 * <h3>初始化脚本</h3>
 * <p>
 * 如果插件的 classpath 中存在 {@code schema.sql}，将自动执行。
 * </p>
 */
@Slf4j
public class PluginDataSourceRegistrar {

    private static final String PROP_AUTO_DATASOURCE = "lingframe.plugin.auto-datasource";
    private static final String PROP_DATASOURCE_URL = "spring.datasource.url";
    private static final String DEFAULT_SCHEMA_LOCATION = "schema.sql";

    /**
     * 在插件容器初始化时注册数据源相关 Bean
     *
     * @param context     插件的 GenericApplicationContext
     * @param classLoader 插件的 ClassLoader
     * @param pluginId    插件 ID（用于日志）
     */
    public static void register(GenericApplicationContext context, ClassLoader classLoader, String pluginId) {
        Environment env = context.getEnvironment();

        // 检查是否禁用自动数据源配置（默认启用）
        boolean autoDataSourceEnabled = env.getProperty(PROP_AUTO_DATASOURCE, Boolean.class, true);
        if (!autoDataSourceEnabled) {
            log.debug("[{}] Plugin auto-datasource configuration is disabled", pluginId);
            return;
        }

        // 检查插件是否配置了独立数据源
        String dataSourceUrl = env.getProperty(PROP_DATASOURCE_URL);
        if (dataSourceUrl == null || dataSourceUrl.isBlank()) {
            log.debug("[{}] Plugin spring.datasource.url not configured, skipping auto-datasource registration",
                    pluginId);
            return;
        }

        log.info("[{}] Detected plugin datasource configuration, registering independent DataSource", pluginId);

        // 注册 DataSourceProperties
        Supplier<DataSourceProperties> propsSupplier = () -> {
            DataSourceProperties props = new DataSourceProperties();
            props.setUrl(env.getProperty(PROP_DATASOURCE_URL));
            props.setUsername(env.getProperty("spring.datasource.username"));
            props.setPassword(env.getProperty("spring.datasource.password"));
            props.setDriverClassName(env.getProperty("spring.datasource.driver-class-name"));
            return props;
        };
        context.registerBean("pluginDataSourceProperties", DataSourceProperties.class, propsSupplier);

        // 注册独立 DataSource（设为 Primary，覆盖可能从父容器继承的）
        Supplier<DataSource> dataSourceSupplier = () -> {
            DataSourceProperties props = context.getBean("pluginDataSourceProperties", DataSourceProperties.class);
            return props.initializeDataSourceBuilder().build();
        };
        context.registerBean("dataSource", DataSource.class, dataSourceSupplier, bd -> bd.setPrimary(true));

        // 检查 schema.sql 是否存在（只查找插件自身资源，不委派给父 ClassLoader）
        URL schemaUrl;
        if (classLoader instanceof URLClassLoader urlClassLoader) {
            // URLClassLoader.findResource 不委派给父 ClassLoader
            schemaUrl = urlClassLoader.findResource(DEFAULT_SCHEMA_LOCATION);
        } else {
            // 回退方案：使用 getResource（会委派）
            schemaUrl = classLoader.getResource(DEFAULT_SCHEMA_LOCATION);
        }

        if (schemaUrl != null) {
            log.info("[{}] Detected {} at {}, registering DataSourceInitializer", pluginId, DEFAULT_SCHEMA_LOCATION,
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
            context.registerBean("pluginDataSourceInitializer", DataSourceInitializer.class, initializerSupplier);
        } else {
            log.debug("[{}] {} not found in plugin, skipping database initialization", pluginId,
                    DEFAULT_SCHEMA_LOCATION);
        }
    }
}
