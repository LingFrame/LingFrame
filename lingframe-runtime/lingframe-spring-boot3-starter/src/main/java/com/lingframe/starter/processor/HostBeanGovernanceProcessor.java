package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.interceptor.HostBeanGovernanceInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 宿主 Bean 治理处理器
 * 拦截宿主应用中的业务 Bean（@Service、@Component、@Controller 等），
 * 应用治理拦截器，让它们经过 LingFrame 核心的权限检查和审计
 */
@Slf4j
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HostBeanGovernanceProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;
    private GovernanceKernel governanceKernel;
    private LingFrameProperties properties;

    // 需要被拦截的注解类型
    private static final Set<Class<? extends Annotation>> GOVERNANCE_ANNOTATIONS = new HashSet<>(Arrays.asList(
            Service.class,
            Component.class,
            Controller.class,
            Repository.class,
            RestController.class
    ));

    // 不需要拦截的 Bean 名称前缀
    private static final Set<String> EXCLUDED_BEAN_PREFIXES = new HashSet<>(Arrays.asList(
            "org.springframework",
            "lingframe",
            "spring",
            "server",
            "tomcat",
            "servlet",
            "filter",
            "listener",
            "handlerMapping",
            "handlerAdapter",
            "viewResolver",
            "multipartResolver",
            "localeResolver",
            "themeResolver",
            "exceptionResolver",
            "messageSource",
            "applicationContext",
            "beanFactory",
            "environment",
            "conversionService",
            "validator",
            "dataSource",
            "entityManagerFactory",
            "transactionManager",
            "cacheManager",
            "taskExecutor",
            "threadPool",
            "async",
            "scheduled",
            "webMvcConfigurer",
            "webFluxConfigurer",
            "securityFilterChain",
            "authenticationManager",
            "userDetailsService",
            "passwordEncoder",
            "jackson",
            "objectMapper",
            "messageConverter",
            "restTemplate",
            "webClient",
            "feign",
            "ribbon",
            "eureka",
            "consul",
            "nacos",
            "config",
            "properties",
            "yml",
            "logging",
            "actuator",
            "management",
            "metrics",
            "health",
            "info",
            "prometheus"
    ));

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        // 延迟获取核心组件，确保 ApplicationContext 已经准备好
        try {
            this.governanceKernel = applicationContext.getBean(GovernanceKernel.class);
            this.properties = applicationContext.getBean(LingFrameProperties.class);
        } catch (Exception e) {
            log.error("Failed to get core beans for governance", e);
        }
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 如果核心组件未准备好，直接返回
        if (governanceKernel == null || properties == null) {
            log.debug("GovernanceKernel or Properties not ready, skipping bean: {}", beanName);
            return bean;
        }

        // 检查是否启用了宿主 Bean 治理
        if (!properties.getHostGovernance().isEnabled()) {
            log.debug("Host governance is disabled, skipping bean: {}", beanName);
            return bean;
        }

        // 检查是否需要拦截
        boolean shouldGovern = shouldGovern(bean, beanName);
        log.debug("Checking bean: {} ({}), shouldGovern: {}", beanName, bean.getClass().getSimpleName(), shouldGovern);
        if (!shouldGovern) {
            return bean;
        }

        // 创建代理
        try {
            ProxyFactory proxyFactory = new ProxyFactory(bean);
            proxyFactory.setProxyTargetClass(true); // 强制使用 CGLIB
            proxyFactory.addAdvice(new HostBeanGovernanceInterceptor(
                governanceKernel,
                properties.getHostGovernance().isGovernInternalCalls(),
                properties.getHostGovernance().isCheckPermissions()
            ));
            Object proxy = proxyFactory.getProxy();
            log.info("[Governance] Successfully governed host bean: {} ({})", beanName, bean.getClass().getSimpleName());
            return proxy;
        } catch (Exception e) {
            log.error("Failed to create governance proxy for bean: {}", beanName, e);
            return bean;
        }
    }

    @Override
    public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        return bean;
    }

    /**
     * 判断是否需要治理
     */
    private boolean shouldGovern(Object bean, String beanName) {
        // 1. 排除 Spring 内部 Bean
        if (isExcludedBean(beanName)) {
            return false;
        }

        // 2. 排除已经被代理的 Bean
        if (org.springframework.aop.support.AopUtils.isAopProxy(bean)) {
            return false;
        }

        // 3. 排除已经有 @LingReference 注解的字段的 Bean（这些是插件内部的）
        if (hasLingReference(bean.getClass())) {
            return false;
        }

        // 4. 检查是否有业务注解
        Class<?> targetClass = org.springframework.aop.support.AopUtils.getTargetClass(bean);
        for (Class<? extends Annotation> annotationType : GOVERNANCE_ANNOTATIONS) {
            if (targetClass.isAnnotationPresent(annotationType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否为排除的 Bean
     */
    private boolean isExcludedBean(String beanName) {
        if (beanName == null) {
            return false;
        }
        String lowerName = beanName.toLowerCase();
        for (String prefix : EXCLUDED_BEAN_PREFIXES) {
            if (lowerName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查类是否有 @LingReference 注解的字段
     */
    private boolean hasLingReference(Class<?> clazz) {
        java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
        for (java.lang.reflect.Field field : fields) {
            if (field.isAnnotationPresent(LingReference.class)) {
                return true;
            }
        }
        return false;
    }
}