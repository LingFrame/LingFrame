package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.context.LingContext;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.ling.BusinessInterfaceFilter;
import com.lingframe.core.ling.LingServiceRegistrar;
import com.lingframe.core.ling.LingServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 灵核 Bean 服务契约注册器。
 * <p>
 * 与 {@link LingCoreBeanGovernanceProcessor}(AOP 治理)解耦:后者只做 GOVERN_ONLY 拦截,
 * 本类只做服务契约注册——把灵核里 implements 业务接口的 Bean 注册到 {@link LingServiceRegistry},
 * lingId 标记为 {@link LingCoreConstants#LINGCORE_LING_ID},使灵元通过 @LingReference 反向调用
 * 灵核 Bean 时能被 {@code GlobalServiceRoutingProxy} 反查命中。
 * <p>
 * 注册语义与灵元侧 {@code SpringLingContainer.scanAndRegisterLingServices} 完全对称,
 * 复用 {@link LingServiceRegistrar} + {@link BusinessInterfaceFilter}。
 * <p>
 * 时序:由于 {@code lingCoreContext} Bean 依赖 {@code lingCoreInstance} Bean
 * (依赖 {@code lingLifecycleEngine.bootstrapLingCoreInstance}),BPP 阶段触发时
 * LingContext 可能尚未就绪。因此本类懒加载 registrar,未就绪则跳过(记 debug);
 * 在 {@link ContextRefreshedEvent} 阶段补扫一次兜底,确保所有灵核 Bean 都被注册。
 */
@Slf4j
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LingCoreServiceRegistrarProcessor
        implements BeanPostProcessor, ApplicationContextAware, ApplicationListener<ContextRefreshedEvent> {

    private static final Set<Class<? extends Annotation>> SERVICE_ANNOTATIONS =
            new HashSet<>(Arrays.asList(
                    Service.class,
                    Component.class,
                    Repository.class));

    // 与 LingCoreBeanGovernanceProcessor.EXCLUDED_BEAN_PREFIXES 同步,避免框架内部 Bean 被误注册
    private static final Set<String> EXCLUDED_BEAN_PREFIXES = new HashSet<>(Arrays.asList(
            "org.springframework", "lingframe", "spring", "server", "tomcat", "servlet",
            "filter", "listener", "handlerMapping", "handlerAdapter", "viewResolver",
            "multipartResolver", "localeResolver", "themeResolver", "exceptionResolver",
            "messageSource", "applicationContext", "beanFactory", "environment",
            "conversionService", "validator", "dataSource", "entityManagerFactory",
            "transactionManager", "cacheManager", "taskExecutor", "threadPool", "async"));

    private ApplicationContext applicationContext;
    private volatile LingServiceRegistrar registrar;
    private volatile boolean registrarReady = false;
    private volatile boolean coreScanDone = false;
    private final Set<String> registeredBeanNames = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        tryRegister(bean, beanName);
        return bean;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // ContextRefreshedEvent 阶段补扫兜底,确保所有灵核 Bean 都被注册
        // (BPP 阶段可能因 LingContext 未就绪跳过部分 Bean)
        if (coreScanDone) {
            return;
        }
        if (event.getApplicationContext() != applicationContext) {
            // 只处理根上下文,避免子上下文事件重复触发
            return;
        }
        log.info("[{}] Triggering core bean service registration rescan on ContextRefreshedEvent",
                LingCoreConstants.LINGCORE_LING_ID);
        scanAllCoreBeans();
        coreScanDone = true;
    }

    private void tryRegister(Object bean, String beanName) {
        if (!ensureRegistrarReady()) {
            return;
        }
        if (isExcludedBean(beanName)) {
            return;
        }
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (!isCoreBean(targetClass)) {
            return;
        }
        if (hasLingReference(targetClass)) {
            // 灵元消费者 Bean(带 @LingReference 字段)不作为生产者注册
            return;
        }
        if (!hasServiceAnnotation(targetClass)) {
            return;
        }
        if (registeredBeanNames.contains(beanName)) {
            return;
        }
        doRegister(bean, beanName, targetClass);
    }

    private void scanAllCoreBeans() {
        if (!ensureRegistrarReady()) {
            log.debug("LingServiceRegistrar still not ready on ContextRefreshedEvent, skip rescan");
            return;
        }
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            if (isExcludedBean(beanName) || registeredBeanNames.contains(beanName)) {
                continue;
            }
            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> targetClass = AopUtils.getTargetClass(bean);
                if (!isCoreBean(targetClass)) {
                    continue;
                }
                if (hasLingReference(targetClass)) {
                    continue;
                }
                if (!hasServiceAnnotation(targetClass)) {
                    continue;
                }
                doRegister(bean, beanName, targetClass);
            } catch (Exception e) {
                log.warn("Failed to register core bean [{}] as service producer: {}", beanName, e.getMessage());
            }
        }
        log.info("[{}] Core bean service registration rescan done, total registered: {}",
                LingCoreConstants.LINGCORE_LING_ID, registeredBeanNames.size());
    }

    private boolean ensureRegistrarReady() {
        if (registrarReady && registrar != null) {
            return true;
        }
        LingContext lingContext;
        try {
            lingContext = applicationContext.getBean(LingContext.class);
        } catch (Exception e) {
            log.debug("LingContext not available yet, skip core bean registration");
            return false;
        }
        if (!(lingContext instanceof DefaultLingContext)) {
            log.debug("LingContext is not DefaultLingContext, skip core bean registration");
            return false;
        }
        DefaultLingContext coreCtx = (DefaultLingContext) lingContext;
        if (!LingCoreConstants.LINGCORE_LING_ID.equals(coreCtx.getLingId())) {
            // 灵元子上下文也有 LingContext Bean,只处理灵核级的
            return false;
        }
        LingServiceRegistry registry = coreCtx.getLingServiceRegistry();
        BusinessInterfaceFilter interfaceFilter = BusinessInterfaceFilter.builder()
                .ecosystemExcluded(LingServiceRegistrar.defaultEcosystemExcluded())
                .build();
        boolean implicitRegistration = true;
        try {
            LingFrameInfo lingFrameInfo =
                    applicationContext.getBean(LingFrameInfo.class);
            implicitRegistration = lingFrameInfo.isImplicitRegistration();
        } catch (Exception e) {
            log.debug("LingFrameInfo not available, fallback to default implicitRegistration=true");
        }
        registrar = LingServiceRegistrar.forCore(registry, interfaceFilter, implicitRegistration, coreCtx);
        registrarReady = true;
        log.info("[{}] LingServiceRegistrar ready for core bean registration", LingCoreConstants.LINGCORE_LING_ID);
        return true;
    }

    private void doRegister(Object bean, String beanName, Class<?> targetClass) {
        registrar.register(LingCoreConstants.LINGCORE_LING_ID, bean, targetClass);
        registeredBeanNames.add(beanName);
        log.info("[{}] Registered core bean [{}] as service producer, class={}",
                LingCoreConstants.LINGCORE_LING_ID, beanName, targetClass.getName());
    }

    private boolean isCoreBean(Class<?> targetClass) {
        ClassLoader beanCl = targetClass.getClassLoader();
        if (beanCl == null) {
            // JDK 类等 bootstrap 加载的,不是灵核业务 Bean
            return false;
        }
        ClassLoader appCl = applicationContext.getClassLoader();
        if (appCl == null) {
            appCl = Thread.currentThread().getContextClassLoader();
        }
        return beanCl.equals(appCl);
    }

    private boolean isExcludedBean(String beanName) {
        if (beanName == null) {
            return true;
        }
        String lowerName = beanName.toLowerCase();
        for (String prefix : EXCLUDED_BEAN_PREFIXES) {
            if (lowerName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasServiceAnnotation(Class<?> targetClass) {
        for (Class<? extends Annotation> annotationType : SERVICE_ANNOTATIONS) {
            if (targetClass.isAnnotationPresent(annotationType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查类是否有 @LingReference 字段(那些是消费者 Bean,不作为生产者注册)。
     * 与 {@link LingCoreBeanGovernanceProcessor#hasLingReference} 逻辑一致。
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