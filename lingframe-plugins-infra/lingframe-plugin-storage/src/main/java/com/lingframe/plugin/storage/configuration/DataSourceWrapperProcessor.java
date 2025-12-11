package com.lingframe.plugin.storage.configuration;

import com.lingframe.api.security.PermissionService;
import com.lingframe.plugin.storage.proxy.LingDataSourceProxy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceWrapperProcessor implements BeanPostProcessor {

    private final ApplicationContext applicationContext;

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 如果 Bean 是 DataSource，就把它包一层
        if (bean instanceof DataSource) {
            log.info(">>>>>> [LingFrame] Wrapping DataSource: {}", beanName);

            // 从 Core 获取 PermissionService (这里假设 PermissionService Bean 可见)
            // 注意：因为基础设施插件与 Core 在类加载器上通常有一定隔离，这里需要确保 API 包是共享的
            PermissionService permissionService = applicationContext.getBean(PermissionService.class);

            return new LingDataSourceProxy((DataSource) bean, permissionService);
        }
        return bean;
    }
}