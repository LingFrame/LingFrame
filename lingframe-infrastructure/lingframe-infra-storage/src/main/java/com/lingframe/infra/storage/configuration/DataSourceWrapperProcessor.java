package com.lingframe.infra.storage.configuration;

import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;

@Slf4j
@RequiredArgsConstructor
// 如果灵核没引 JDBC 依赖，这个类直接跳过，不会报错，也不会生效。
@ConditionalOnClass(DataSource.class)
// ✅ 跟随框架总开关：框架开启，我就必须开启
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
// ✅ 确保 LingFrame 的包装器最先执行，防止被其他 BeanPostProcessor 绕过
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataSourceWrapperProcessor implements BeanPostProcessor {

    private final ApplicationContext applicationContext;

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 如果 Bean 是 DataSource，就把它包一层
        if (bean instanceof DataSource) {
            // 防止重复包装
            if (bean instanceof LingDataSourceProxy) {
                log.debug("[LingFrame] DataSource {} already wrapped, skipping", beanName);
                return bean;
            }

            log.info(">>>>>> [LingFrame] Wrapping DataSource: {}", beanName);

            // 从 Core 获取 PermissionService (这里假设 PermissionService Bean 可见)
            // 注意：因为基础设施单元与 Core 在类加载器上通常有一定隔离，这里需要确保 API 包是共享的
            PermissionService permissionService = applicationContext.getBean(PermissionService.class);

            return new LingDataSourceProxy((DataSource) bean, permissionService);
        }
        return bean;
    }
}