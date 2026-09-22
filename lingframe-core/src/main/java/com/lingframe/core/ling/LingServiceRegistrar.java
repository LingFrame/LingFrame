package com.lingframe.core.ling;

import com.lingframe.api.annotation.LingService;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.core.context.DefaultLingContext;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 灵元服务统一注册器。
 * <p>
 * 统一处理「显式注解服务」与「隐式接口服务」两条注册路径，
 * 由 Spring 适配层与 native 适配层共用，消除原散在 {@code SpringLingContainer} 的注册逻辑。
 * <p>
 * 边界：本类只负责「扫到什么就注册什么」的元数据登记，不负责 Bean 扫描本身——
 * 扫描的边界判定（ClassLoader 隔离、源路径比对）仍由适配层持有。
 * <p>
 * 注册语义：
 * <ul>
 *   <li>显式 {@code @LingService}：短 ID 由注解 {@code id()} 给出；{@code id()} 留空时按
 *       「TYPE 取 SimpleName 首字母小写、METHOD 取方法名」推导</li>
 *   <li>隐式接口：灵元 Bean 实现的业务接口（由 {@link BusinessInterfaceFilter} 判定），
 *       每个接口的 public 方法都注册为 FQSID = {@code lingId:interfaceName}</li>
 *   <li>受 {@code implicitRegistration} 开关控制：关闭时仅显式注解服务注册，隐式接口跳过</li>
 * </ul>
 */
@Slf4j
public class LingServiceRegistrar {

    private final LingServiceRegistry registry;
    private final BusinessInterfaceFilter interfaceFilter;
    private final boolean implicitRegistration;
    private final DefaultLingContext context;
    /** 注册时携带的初始权重，默认 0（多 provider 场景灵元不自动接流量，需 Dashboard 配置） */
    private final int defaultWeight;

    public LingServiceRegistrar(LingServiceRegistry registry,
            BusinessInterfaceFilter interfaceFilter,
            boolean implicitRegistration) {
        this(registry, interfaceFilter, implicitRegistration, null);
    }

    /**
     * 完整构造：传入 DefaultLingContext 以走 registerProtocolService 统一注册路径。
     * <p>
     * 委域边界：ctx 非空时注册走 ctx.registerProtocolService（做 metadataCache + implClassName +
     * 实例绑定三件事）；ctx 为空时退回直接调 registry（仅 metadataCache + implClassName，
     * 实例绑定丢失——演练场 hasServiceMethod 会判 false）。适配层应始终传 ctx。
     * <p>
     * 默认 weight=0（灵元场景）。灵核侧应使用 {@link #forCore(LingServiceRegistry, BusinessInterfaceFilter, boolean, DefaultLingContext)}
     * 工厂方法携带 weight=100 注册。
     */
    public LingServiceRegistrar(LingServiceRegistry registry,
            BusinessInterfaceFilter interfaceFilter,
            boolean implicitRegistration,
            DefaultLingContext context) {
        this(registry, interfaceFilter, implicitRegistration, context, 0);
    }

    /**
     * 灵核专用工厂方法。
     * <p>
     * 灵核侧（{@code LingCoreServiceRegistrarProcessor}）用此方法注册灵核 Bean，
     * 携带默认权重 100（灵核 baseline 默认承接全量流量，路由层不引用实现方身份，
     * 身份在注册时沉淀为 weight 数值）。
     */
    public static LingServiceRegistrar forCore(LingServiceRegistry registry,
            BusinessInterfaceFilter interfaceFilter,
            boolean implicitRegistration,
            DefaultLingContext context) {
        return new LingServiceRegistrar(registry, interfaceFilter, implicitRegistration, context, 100);
    }

    /**
     * 显式指定初始权重的构造函数。
     * <p>
     * 灵核侧用此构造函数传 {@code weight=100}；灵元侧用旧构造函数，默认 {@code weight=0}。
     *
     * @param defaultWeight 注册时携带的初始权重 0-100
     */
    public LingServiceRegistrar(LingServiceRegistry registry,
            BusinessInterfaceFilter interfaceFilter,
            boolean implicitRegistration,
            DefaultLingContext context,
            int defaultWeight) {
        this.registry = registry;
        this.interfaceFilter = interfaceFilter != null ? interfaceFilter : BusinessInterfaceFilter.coreDefaults();
        this.implicitRegistration = implicitRegistration;
        this.context = context;
        this.defaultWeight = Math.max(0, Math.min(100, defaultWeight));
    }

    /**
     * 注册一个灵元 Bean 的所有服务契约。
     * <p>
     * 依次处理：显式 {@code @LingService} 方法/类型 → 隐式接口实现（受开关控制）。
     * <p>
     * 双 FQSID 有意设计：当灵元 Bean 既标 TYPE 级 {@code @LingService(id="userService")} 又
     * 实现业务接口（如 {@code com.example.UserService}）时，同一方法会注册两个 FQSID：
     * <ul>
     *   <li>{@code lingId:userService}——显式短 ID 键，支持按短 ID 路由</li>
     *   <li>{@code lingId:com.example.UserService}——隐式接口全限定名键，支持按接口类型路由</li>
     * </ul>
     * 两条路由路径并存，调用方按短 ID 或接口类型都能命中。反向索引中同一灵元会被两个契约 ID
     * 索引到，{@code evict} 时按灵元维度清理两份（幂等）。这是有意设计，非缺陷。
     *
     * @param lingId   灵元 ID
     * @param bean     灵元 Bean 实例
     * @param beanClass 灵元 Bean 类（用于反射注解与接口）
     */
    public void register(String lingId, Object bean, Class<?> beanClass) {
        if (lingId == null || bean == null || beanClass == null) {
            return;
        }

        // 1. 显式 @LingService 注册（FQSID: [lingId]:[shortId]）
        registerExplicitLingServices(lingId, bean, beanClass);

        // 2. 隐式接口注册（受 implicitRegistration 开关控制）
        if (implicitRegistration) {
            registerImplicitInterfaceServices(lingId, bean, beanClass);
        } else {
            log.debug("[Registrar] implicit registration disabled for ling [{}], skipping interface scan", lingId);
        }
    }

    /**
     * 显式注解服务注册：扫 beanClass 所有 public 方法上的 {@code @LingService}。
     * <p>
     * 短 ID 推导规则：
     * <ul>
     *   <li>注解 {@code id()} 非空：直接用</li>
     *   <li>注解 {@code id()} 空且标在 TYPE 上：取类型 SimpleName 首字母小写</li>
     *   <li>注解 {@code id()} 空且标在 METHOD 上：取方法名</li>
     * </ul>
     * 健壮性：反射过程中任何异常（SecurityException 等）应记日志不崩，
     * 避免单个病态 Bean 拖垮整个灵元的服务注册。
     */
    // TODO(组合注解支持): 本类用 method.getAnnotation() 反射查 @LingService（Java 原生），
    // 不识别 Spring 元注解继承（即 @MyService 内嵌 @LingService 的组合注解）。
    // 这是有意约束——core 不依赖 Spring。若未来允许灵元用组合注解，
    // 应在适配层做前置转换（如 AnnotatedElementUtils.findMergedAnnotation 展开为直接标注），
    // 或在 LingServiceRegistrar 接受一个 AnnotationFinder 策略接口由适配层注入。
    private void registerExplicitLingServices(String lingId, Object bean, Class<?> beanClass) {
        // 1. TYPE 级 @LingService：标注在类上，把该类所有业务接口的 public 方法注册为同一短 ID 的服务契约
        LingService typeAnno = beanClass.getAnnotation(LingService.class);
        if (typeAnno != null) {
            String typeShortId = resolveShortIdFromType(typeAnno, beanClass);
            String typeFqsid = lingId + ":" + typeShortId;
            // 递归收集所有业务接口（含父接口），确保按父接口类型路由也能命中
            Set<Class<?>> allBusinessInterfaces = collectBusinessInterfaces(beanClass);
            if (allBusinessInterfaces.isEmpty()) {
                // 防御：TYPE 级 @LingService 但灵元未实现任何业务接口——
                // 内层循环会全空跑，零服务被静默注册。记 warn 让用户可见，
                // 避免误标 TYPE 注解 of 灵元得到看不见的 no-op。
                log.warn("[Registrar] Ling [{}] annotated with TYPE level @LingService but does not implement any business interface, "
                                + "shortId=[{}], no method registered. Please verify if implements is missing, or use METHOD level annotation instead.",
                        lingId, typeShortId);
            }
            for (Class<?> iface : allBusinessInterfaces) {
                for (Method ifaceMethod : iface.getMethods()) {
                    try {
                        Method implMethod = beanClass.getMethod(
                                ifaceMethod.getName(), ifaceMethod.getParameterTypes());
                        registerViaContext(typeFqsid, bean, implMethod);
                        log.info("[Registrar] Explicit type registration: ling=[{}], fqsid=[{}], method=[{}]",
                                lingId, typeFqsid, implMethod.getName());
                    } catch (NoSuchMethodException ignored) {
                        // 接口方法在 Bean 上未实现（抽象/桥接方法等），记 debug 利于排查
                        log.debug("[Registrar] Ling [{}] TYPE level explicit registration skipped unimplemented method: iface=[{}], method=[{}]",
                                lingId, iface.getName(), ifaceMethod.getName());
                    } catch (Exception e) {
                        log.warn("[Registrar] Ling [{}] type level explicit registration exception: iface=[{}], method=[{}], err=[{}]",
                                lingId, iface.getName(), ifaceMethod.getName(), e.getMessage());
                    }
                }
            }
        }

        // 2. METHOD 级 @LingService：标注在方法上，按方法粒度注册
        for (Method method : beanClass.getMethods()) {
            try {
                LingService anno = method.getAnnotation(LingService.class);
                if (anno == null) {
                    continue;
                }
                String shortId = resolveShortId(anno, method, beanClass);
                String fqsid = lingId + ":" + shortId;
                registerViaContext(fqsid, bean, method);
                log.info("[Registrar] Explicit alias registration: ling=[{}], fqsid=[{}], method=[{}]",
                        lingId, fqsid, method.getName());
            } catch (Exception e) {
                // 反射异常记日志不崩——单个病态方法不应拖垮整个注册
                log.warn("[Registrar] Ling [{}] explicit service registration scan exception: method=[{}], err=[{}]",
                        lingId, method.getName(), e.getMessage());
            }
        }
    }

    /**
     * 隐式接口注册：扫 beanClass 实现的业务接口（含父接口），每个接口的 public 方法都注册为契约。
     * 健壮性：反射过程中任何异常应记日志不崩。
     */
    private void registerImplicitInterfaceServices(String lingId, Object bean, Class<?> beanClass) {
        // 递归收集所有业务接口（含父接口），确保按父接口类型路由也能命中
        Set<Class<?>> allBusinessInterfaces = collectBusinessInterfaces(beanClass);
        for (Class<?> iface : allBusinessInterfaces) {
            for (Method ifaceMethod : iface.getMethods()) {
                try {
                    Method implMethod = beanClass.getMethod(
                            ifaceMethod.getName(), ifaceMethod.getParameterTypes());
                    String fqsid = lingId + ":" + iface.getName();
                    registerViaContext(fqsid, bean, implMethod);
                    log.info("[Registrar] Implicit registration: ling=[{}], fqsid=[{}], method=[{}]",
                            lingId, fqsid, implMethod.getName());
                } catch (NoSuchMethodException ignored) {
                    // 接口方法在 Bean 上未实现（抽象/桥接方法等），记 debug 利于排查
                    log.debug("[Registrar] Ling [{}] implicit registration skipped unimplemented method: iface=[{}], method=[{}]",
                            lingId, iface.getName(), ifaceMethod.getName());
                } catch (Exception e) {
                    // 其他反射异常记日志不崩
                    log.warn("[Registrar] Ling [{}] implicit interface registration scan exception: iface=[{}], method=[{}], err=[{}]",
                            lingId, iface.getName(), ifaceMethod.getName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 递归收集 beanClass 实现的所有业务接口（含父接口）。
     * <p>
     * {@code beanClass.getInterfaces()} 只返回直接实现的接口，不包含父接口。
     * 本方法递归遍历接口继承树，确保按父接口类型路由也能命中。
     */
    private Set<Class<?>> collectBusinessInterfaces(Class<?> beanClass) {
        Set<Class<?>> result = new LinkedHashSet<>();
        collectBusinessInterfacesRecursive(beanClass, result);
        return result;
    }

    private void collectBusinessInterfacesRecursive(Class<?> clazz, Set<Class<?>> accumulator) {
        if (clazz == null || clazz == Object.class) {
            return;
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            if (interfaceFilter.isBusinessInterface(iface) && accumulator.add(iface)) {
                // 递归收集该接口的父接口
                collectBusinessInterfacesRecursive(iface, accumulator);
            }
        }
        // 沿类继承链向上查找（父类实现的接口也需要收集）
        collectBusinessInterfacesRecursive(clazz.getSuperclass(), accumulator);
    }

    /**
     * 推导显式注解服务的短 ID（METHOD 级）。
     */
    private String resolveShortId(LingService anno, Method method, Class<?> beanClass) {
        String explicit = anno.id();
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        // 默认按方法名推导
        return method.getName();
    }

    /**
     * 推导 TYPE 级 @LingService 的短 ID。
     * 规则：注解 id() 非空直接用；留空取类型 SimpleName 首字母小写（如 UserService -> userService）。
     * 健壮性：匿名类 SimpleName 为空走 "anonymous" 兜底——多个匿名类会碰撞相同 FQSID，
     * 此处记 warn 让排查可见。生产场景匿名类标 TYPE 级 @LingService 是病态用法，应显式填 id()。
     */
    private String resolveShortIdFromType(LingService anno, Class<?> beanClass) {
        String explicit = anno.id();
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String simpleName = beanClass.getSimpleName();
        if (simpleName == null || simpleName.isEmpty()) {
            log.warn("[Registrar] Anonymous class annotated with TYPE level @LingService but missing id(), fallback short ID to 'anonymous'. "
                    + "Multiple anonymous classes will collide with the same FQSID causing override. It's recommended to set id() explicitly. beanClass=[{}]",
                    beanClass.getName());
            return "anonymous";
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private String[] toTypeNames(Class<?>[] paramTypes) {
        String[] names = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            names[i] = paramTypes[i].getName();
        }
        return names;
    }

    /**
     * 委派注册：ctx 非空时走 DefaultLingContext.registerProtocolService（做 metadataCache +
     * implClassName + 实例绑定三件事，演练场 hasServiceMethod 依赖实例绑定）；
     * ctx 为空时退回直接调 registry（仅 metadataCache + implClassName，实例绑定丢失）。
     * 适配层应始终传 ctx 避免演练场取不到服务。
     * <p>
     * 路由升维：无论走哪条路径，都同步调用 {@code registerProvider} 登记 provider 索引，
     * 使 {@code ProviderWeightRouter} 能按契约查到所有提供方。
     * 版本标识从绑定实例上下文派生（{@code lingId:version}），保证同灵元多版本并存时候选可区分。
     */
    private void registerViaContext(String fqsid, Object bean, Method method) {
        if (context != null) {
            context.registerProtocolService(fqsid, bean, method);
        } else {
            // 兜底：无 ctx 时直接调 registry，保留 metadataCache + implClassName
            registry.registerServiceMetadata(fqsid, method.getName(),
                    toTypeNames(method.getParameterTypes()), method.getReturnType().getName());
            registry.registerImplementationClassName(fqsid, bean.getClass().getName());
        }
        // 路由升维：从 FQSID 提取 contractId，登记 provider 索引
        // 幂等：同一 (contractId, providerKey) 重复注册 weight 以最新为准
        String lingId = extractLingIdFromFqsid(fqsid);
        String contractId = extractContractIdFromFqsid(fqsid);
        if (lingId != null && contractId != null) {
            // 版本真源：从绑定实例的上下文派生——同一灵元多版本并存时以 lingId:version 区分候选；
            // 灵核上下文（lingcore-app）无版本概念，注册键保持裸 lingId
            String version = LingCoreConstants.LINGCORE_LING_ID.equals(lingId) ? null
                    : (context != null ? context.getVersion() : null);
            registry.registerProvider(contractId, lingId, version, defaultWeight);
        }
    }

    /**
     * 从 FQSID 提取 lingId（冒号前部分）。
     */
    private String extractLingIdFromFqsid(String fqsid) {
        if (fqsid == null) {
            return null;
        }
        int idx = fqsid.indexOf(':');
        return idx > 0 ? fqsid.substring(0, idx) : null;
    }

    /**
     * 从 FQSID 提取 contractId（冒号后部分）。
     */
    private String extractContractIdFromFqsid(String fqsid) {
        if (fqsid == null) {
            return null;
        }
        int idx = fqsid.indexOf(':');
        return idx > 0 && idx < fqsid.length() - 1 ? fqsid.substring(idx + 1) : null;
    }

    /**
     * 卸载时清理该灵元所有服务契约。
     * 委派给 {@link LingServiceRegistry#evict(String)}。
     */
    public void evict(String lingId) {
        registry.evict(lingId);
    }

    /**
     * 默认生态环境排除前缀（适配层构建 {@link BusinessInterfaceFilter} 时可参考）。
     */
    public static Collection<String> defaultEcosystemExcluded() {
        Set<String> prefixes = new LinkedHashSet<>();
        prefixes.add("org.springframework.");
        prefixes.add("io.micrometer.");
        prefixes.add("com.zaxxer.");
        prefixes.add("com.lingframe.starter.");
        return prefixes;
    }
}
