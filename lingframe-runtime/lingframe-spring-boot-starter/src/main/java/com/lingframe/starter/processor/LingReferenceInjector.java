package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.exception.LingRuntimeException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

/**
 * 灵珑服务引用注入器。
 * <p>
 * 🔥 边界收敛：本类仅负责「按注解字段类型 + 路由锚点取代理」，
 * 不再承载治理入参（超时/降级/重试已收敛到 YAML references 分区，
 * 由 StandardGovernancePolicyProvider 在 P2 阶段覆到 GovernanceDecision）。
 * <p>
 * 删除项：原 L101-131 的「声明式 Fallback 包装代理」——降级语义归
 * ResilienceGovernanceFilter + GovernancePolicy.references.fallbackValue。
 * <p>
 * BPP 二次扫：灵核级 BPP 在 Bean 初始化前 LingContext 可能未就绪，
 * postProcessAfterInitialization 二次扫兜底确保注入不漏。
 */
@Slf4j
public class LingReferenceInjector implements BeanPostProcessor, ApplicationContextAware {

    private final String currentLingId; // 记录当前环境的灵元ID
    private ApplicationContext applicationContext;
    private LingContext lingContext; // 懒加载

    public LingReferenceInjector(String currentLingId) {
        this.currentLingId = currentLingId;
    }

    /** 携带预置 {@link LingContext} 的构造（灵元容器装配路径使用）；{@link #lingContext} 为空时按需解析 */
    public LingReferenceInjector(String currentLingId, LingContext lingContext) {
        this.currentLingId = currentLingId;
        this.lingContext = lingContext;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 懒加载获取 LingContext
     */
    private LingContext getLingContext() {
        if (lingContext == null && applicationContext != null) {
            try {
                lingContext = applicationContext.getBean(LingContext.class);
            } catch (Exception e) {
                log.debug("LingContext not available yet");
            }
        }
        return lingContext;
    }

    /**
     * 确保在 AOP 代理创建之前，把属性注入到原始对象(Target)中。
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, @NonNull String beanName) throws BeansException {
        return tryInject(bean);
    }

    /**
     * 灵核级 BPP 二次扫兜底：LingContext 在 BeforeInitialization 阶段可能未就绪，
     * AfterInitialization 阶段再扫一次确保注入不漏。
     * <p>
     * 注：灵元级 BPP（LingContext 已由子上下文注册）无需此兜底，
     * 此方法对灵元级 BPP 调用是幂等的——字段非空时 injectService 自身会跳过。
     */
    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        return tryInject(bean);
    }

    private Object tryInject(Object bean) {
        LingContext ctx = getLingContext();
        if (ctx == null) {
            return bean; // LingContext 未准备好，跳过；AfterInitialization 阶段会兜底
        }

        Class<?> clazz = bean.getClass();

        // 递归处理所有字段 (包括父类)
        ReflectionUtils.doWithFields(clazz, field -> {
            LingReference annotation = field.getAnnotation(LingReference.class);
            if (annotation != null) {
                injectService(bean, field, annotation, ctx);
            }
        });

        return bean;
    }

    private void injectService(Object bean, Field field, LingReference annotation, LingContext ctx) {
        try {
            field.setAccessible(true);

            // 【防御】非 null 字段视为已满足——跳过注入。
            // 此守卫双重作用：(1) 被 XML/@Autowired 预填的字段不被覆盖；
            // (2) Before/After 二次扫幂等——首扫注入后 After 阶段读到非 null 即跳过。
            // 语义代价：用户故意预置为非 null 哨兵的字段也会被跳过（契约同 Spring
            // @Autowired(required=false)，以 null 为「未注入」标识）。
            if (field.get(bean) != null) {
                log.debug("Field {} is already injected, skipping LingReference injection.", field.getName());
                return;
            }

            Class<?> serviceType = field.getType();

            // 【接口类型校验】@LingReference 只能注入接口类型——路由代理基于 JDK Proxy，非接口无法代理
            if (!serviceType.isInterface()) {
                log.warn("[LingReference] field {}.{} type [{}] is not interface, skipping (JDK Proxy requires interface)",
                        bean.getClass().getSimpleName(), field.getName(), serviceType.getName());
                return;
            }

            // 路由收敛：用带锚点重载的 getService，把 lingId/serviceId 锚心透到 GlobalServiceRoutingProxy
            Object proxy = ctx.getService(serviceType, annotation.lingId(), annotation.serviceId())
                    .orElseThrow(() -> new LingRuntimeException(currentLingId,
                            "Failed to resolve service reference for type: " + serviceType.getName()
                                    + " (lingId=" + annotation.lingId() + ", serviceId=" + annotation.serviceId() + ")"));

            field.set(bean, proxy);
            log.info("Injected @LingReference for field: {}.{} (lingId={}, serviceId={})",
                    bean.getClass().getSimpleName(), field.getName(),
                    annotation.lingId(), annotation.serviceId());
        } catch (IllegalAccessException e) {
            log.error("Failed to inject @LingReference", e);
        }
    }
}
