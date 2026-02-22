package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.interceptor.LingCoreBeanGovernanceInterceptor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 灵核 Bean 治理处理器
 * 拦截灵核应用中的业务 Bean（@Service、@Component、@Controller 等），
 * 应用治理拦截器，让它们经过 LingFrame 核心的权限检查和审计
 */
@Slf4j
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LingCoreBeanGovernanceProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;
    private GovernanceKernel governanceKernel;
    private LingFrameProperties properties;

    // 需要被拦截的注解类型（排除 Controller/RestController，由 LingWebGovernanceInterceptor 处理）
    private static final Set<Class<? extends Annotation>> GOVERNANCE_ANNOTATIONS = new HashSet<>(Arrays.asList(
            Service.class,
            Component.class,
            Repository.class));

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
            "prometheus"));

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        // 不在这里获取 Bean，避免过早初始化导致 BeanPostProcessor 警告
    }

    /**
     * 懒加载获取 GovernanceKernel
     */
    private GovernanceKernel getGovernanceKernel() {
        if (governanceKernel == null && applicationContext != null) {
            try {
                governanceKernel = applicationContext.getBean(GovernanceKernel.class);
            } catch (Exception e) {
                log.debug("GovernanceKernel not available yet");
            }
        }
        return governanceKernel;
    }

    /**
     * 懒加载获取 LingFrameProperties
     */
    private LingFrameProperties getProperties() {
        if (properties == null && applicationContext != null) {
            try {
                properties = applicationContext.getBean(LingFrameProperties.class);
            } catch (Exception e) {
                log.debug("LingFrameProperties not available yet");
            }
        }
        return properties;
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        // 懒加载获取核心组件
        GovernanceKernel kernel = getGovernanceKernel();
        LingFrameProperties props = getProperties();

        // 如果核心组件未准备好，直接返回
        if (kernel == null || props == null) {
            return bean;
        }

        // 检查是否启用了灵核 Bean 治理
        if (!props.getLingCoreGovernance().isEnabled()) {
            return bean;
        }

        // 检查是否需要拦截
        boolean shouldGovern = shouldGovern(bean, beanName);
        if (!shouldGovern) {
            return bean;
        }

        // 创建代理
        try {
            ProxyFactory proxyFactory = new ProxyFactory(bean);
            proxyFactory.setProxyTargetClass(true); // 强制使用 CGLIB
            proxyFactory.addAdvice(new LingCoreBeanGovernanceInterceptor(
                    kernel,
                    props.getLingCoreGovernance().isGovernInternalCalls(),
                    props.getLingCoreGovernance().isCheckPermissions()));
            Object proxy = proxyFactory.getProxy();
            log.info("[Governance] Successfully governed LINGCORE bean: {} ({})", beanName,
                    bean.getClass().getSimpleName());
            return proxy;
        } catch (Exception e) {
            log.error("Failed to create governance proxy for bean: {}", beanName, e);
            return bean;
        }
    }

    @Override
    public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName)
            throws BeansException {
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
        if (AopUtils.isAopProxy(bean)) {
            return false;
        }

        // 3. 排除已经有 @LingReference 注解的字段的 Bean（这些是单元内部的）
        if (hasLingReference(bean.getClass())) {
            return false;
        }

        // 4. 检查是否有业务注解
        Class<?> targetClass = AopUtils.getTargetClass(bean);
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
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (field.isAnnotationPresent(LingReference.class)) {
                return true;
            }
        }
        return false;
    }
}