package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.dto.InvokeResultDTO;
import com.lingframe.dashboard.dto.ServiceMetadataDTO;
import com.lingframe.dashboard.util.ParameterParsingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 服务演练场
 * <p>
 * 提供灵元服务元数据查询和真实调用能力，
 * 供 Dashboard 前端展示服务列表并执行调用验证。
 */
@Slf4j
@RequiredArgsConstructor
public class ServicePlaygroundService {

    private final LingServiceRegistry lingServiceRegistry;
    private final LingRepository lingRepository;
    private final InvocationPipelineEngine pipelineEngine;
    private final ObjectMapper objectMapper;
    private final CanaryRouter canaryRouter;

    /**
     * 获取指定灵元的所有服务元数据
     * <p>
     * 多版本场景下，会遍历所有活跃实例，对每个方法判定它在哪些版本上可用，
     * 填充 {@link ServiceMetadataDTO.MethodMetadata#getVersions()} 字段。
     * 前端据此按版本分组展示，调用时指定目标版本。
     */
    public List<ServiceMetadataDTO> getServices(String lingId) {
        List<String> fqsidList = lingServiceRegistry.getServicesByLingId(lingId);
        if (fqsidList == null || fqsidList.isEmpty()) {
            return Collections.emptyList();
        }

        // 收集所有活跃实例（含稳定版与金丝雀），用于判定方法的版本归属
        List<LingInstance> activeInstances = resolveActiveInstances(lingId);

        List<ServiceMetadataDTO> allDtos = fqsidList.stream().map(fqsid -> {
            String className = lingServiceRegistry.getServiceClassName(fqsid);
            List<String> methodSignatures = lingServiceRegistry.getProviderMethods(fqsid);

            List<ServiceMetadataDTO.MethodMetadata> methods = methodSignatures.stream()
                    .map(sig -> parseMethodSignature(fqsid, sig))
                    .map(method -> attachVersions(fqsid, className, method, activeInstances))
                    .filter(method -> method.getVersions() != null && !method.getVersions().isEmpty())
                    .collect(Collectors.toList());

            // 所有方法在所有版本上都不可用，跳过该服务
            if (methods.isEmpty()) {
                return null;
            }

            return ServiceMetadataDTO.builder()
                    .fqsid(fqsid)
                    .className(className)
                    .methods(methods)
                    .build();
        }).filter(dto -> dto != null).collect(Collectors.toList());

        // 按注册来源分组：接口服务（FQSID 冒号后含 "."）vs 显式注解服务
        List<ServiceMetadataDTO> interfaceServices = new ArrayList<>();
        List<ServiceMetadataDTO> explicitServices = new ArrayList<>();
        for (ServiceMetadataDTO dto : allDtos) {
            if (isInterfaceService(dto.getFqsid())) {
                interfaceServices.add(dto);
            } else {
                explicitServices.add(dto);
            }
        }

        // 按 className 建立接口服务索引，避免 O(n²) 嵌套查找
        Map<String, List<ServiceMetadataDTO>> intfByClassName = new HashMap<>();
        for (ServiceMetadataDTO intf : interfaceServices) {
            if (intf.getClassName() != null) {
                intfByClassName.computeIfAbsent(intf.getClassName(), k -> new ArrayList<>()).add(intf);
            }
        }

        // 将显式服务的 FQSID 归并到同 className 接口服务的对应方法上
        Set<String> mergedExplicitFqsids = new HashSet<>();
        for (ServiceMetadataDTO explicit : explicitServices) {
            List<ServiceMetadataDTO> matchingIntfs = intfByClassName.get(explicit.getClassName());
            if (matchingIntfs == null) {
                continue;
            }
            boolean anyMethodMerged = false;
            for (ServiceMetadataDTO.MethodMetadata expMethod : explicit.getMethods()) {
                boolean currentMethodMerged = false;
                for (ServiceMetadataDTO intf : matchingIntfs) {
                    for (ServiceMetadataDTO.MethodMetadata intfMethod : intf.getMethods()) {
                        if (intfMethod.getSignature().equals(expMethod.getSignature())) {
                            intfMethod.setAlternateFqsid(explicit.getFqsid());
                            // 合并显式服务方法上的版本信息到接口方法上
                            Set<String> mergedVersions = new LinkedHashSet<>();
                            if (intfMethod.getVersions() != null) {
                                mergedVersions.addAll(intfMethod.getVersions());
                            }
                            if (expMethod.getVersions() != null) {
                                mergedVersions.addAll(expMethod.getVersions());
                            }
                            intfMethod.setVersions(new ArrayList<>(mergedVersions));
                            currentMethodMerged = true;
                            anyMethodMerged = true;
                            break;
                        }
                    }
                    if (currentMethodMerged) {
                        break;
                    }
                }
            }
            if (anyMethodMerged) {
                mergedExplicitFqsids.add(explicit.getFqsid());
            }
        }

        List<ServiceMetadataDTO> result = new ArrayList<>(interfaceServices);
        for (ServiceMetadataDTO explicit : explicitServices) {
            if (!mergedExplicitFqsids.contains(explicit.getFqsid())) {
                result.add(explicit);
            }
        }
        return result;
    }

    /**
     * 解析灵元所有活跃实例，按稳定版优先排序。
     * 用于判定方法在哪些版本上可用。
     */
    private List<LingInstance> resolveActiveInstances(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null || runtime.getInstancePool() == null) {
            return Collections.emptyList();
        }
        List<LingInstance> instances = runtime.getInstancePool().getActiveInstances();
        if (instances == null || instances.isEmpty()) {
            return Collections.emptyList();
        }
        // 稳定版排在最前，便于前端默认选中
        List<LingInstance> sorted = new ArrayList<>(instances);
        sorted.sort((a, b) -> {
            boolean aDefault = a.equals(runtime.getInstancePool().getDefault());
            boolean bDefault = b.equals(runtime.getInstancePool().getDefault());
            if (aDefault && !bDefault) return -1;
            if (!aDefault && bDefault) return 1;
            return 0;
        });
        return sorted;
    }

    /**
     * 判定方法在哪些实例版本上可用，填充 versions 字段。
     * <p>
     * 遍历所有活跃实例，尝试解析方法签名，能解析到则记录该实例的版本号。
     * 返回的方法对象 versions 为空表示在所有版本上都不可用，应被过滤。
     * <p>
     * 判定方式：
     * <ul>
     *   <li>显式注解服务：用注册的实现类名 + 实例 CL 加载判定</li>
     *   <li>接口服务（含 sharedapi）：classCache 中的实现类名可能被多版本覆盖，
     *       不能作为判定依据。改用"检查实例容器中是否有 Bean 实现该接口"判定。
     *       接口由 SharedApiClassLoader 加载，所有版本都能加载到接口类，
     *       但只有实际实现了该接口的版本，容器中才会有对应 Bean。</li>
     * </ul>
     */
    private ServiceMetadataDTO.MethodMetadata attachVersions(String fqsid, String className,
            ServiceMetadataDTO.MethodMetadata method, List<LingInstance> instances) {
        if (instances.isEmpty()) {
            // 无活跃实例时保留方法但版本为空，向后兼容（由调用方过滤）
            return method;
        }
        boolean interfaceService = isInterfaceService(fqsid);
        List<String> availableVersions = new ArrayList<>();
        for (LingInstance instance : instances) {
            ClassLoader cl = instance.getClassLoader();
            if (cl == null) {
                // 无法验证时保留方法，与 isMethodAvailable 行为一致（向后兼容）
                availableVersions.add(instance.getVersion());
                continue;
            }
            boolean available;
            if (interfaceService) {
                // 接口服务：检查实例容器中是否有 Bean 实现该接口且包含目标方法
                available = isInterfaceMethodAvailableInInstance(fqsid, method, instance);
            } else {
                // 显式注解服务：用实现类名 + CL 加载判定
                available = isMethodAvailable(className, method, cl);
            }
            if (available) {
                availableVersions.add(instance.getVersion());
            }
        }
        method.setVersions(availableVersions);
        return method;
    }

    /**
     * 判定接口服务方法在指定实例中是否可用。
     * <p>
     * 从 fqsid 提取接口全限定名，用实例 CL 加载接口类，
     * 然后检查实例容器中是否有 Bean 实现该接口。
     * 接口由 SharedApiClassLoader 加载，所有版本都能加载到，
     * 但只有实际实现了该接口的版本，容器中才会有对应 Bean。
     */
    private boolean isInterfaceMethodAvailableInInstance(String fqsid,
            ServiceMetadataDTO.MethodMetadata method, LingInstance instance) {
        ClassLoader cl = instance.getClassLoader();
        if (cl == null) {
            // 无法验证时保留方法，与 isMethodAvailable 行为一致（向后兼容）
            return true;
        }
        String interfaceName = extractInterfaceName(fqsid);
        try {
            Class<?> iface = Class.forName(interfaceName, false, cl);
            // 检查实例容器中是否有 Bean 实现该接口
            if (!hasBeanImplementingInterface(instance, iface)) {
                return false;
            }
            // 接口方法签名校验：用接口类解析方法
            List<String> paramTypeNames = method.getParameterTypes();
            Class<?>[] paramTypes = new Class<?>[paramTypeNames.size()];
            for (int i = 0; i < paramTypeNames.size(); i++) {
                String typeName = paramTypeNames.get(i).trim();
                Class<?> primitive = ParameterParsingUtils.resolvePrimitiveType(typeName);
                paramTypes[i] = primitive != null ? primitive : Class.forName(typeName, false, cl);
            }
            iface.getMethod(method.getName(), paramTypes);
            return true;
        } catch (Exception e) {
            log.debug("Interface method {} not available in instance {} (version={})",
                    method.getName(), fqsid, instance.getVersion());
            return false;
        }
    }

    /**
     * 检查实例容器中是否有 Bean 实现指定接口。
     * <p>
     * 用接口全限定名字符串比较，绕过 ClassLoader 隔离导致的 isAssignableFrom 失败。
     * 遍历 Bean 的类层级（含 CGLIB 代理的目标类），处理 @Cacheable/@Transactional 等 AOP 代理场景。
     */
    private boolean hasBeanImplementingInterface(LingInstance instance, Class<?> iface) {
        return findBeanClassNameImplementingInterface(instance, iface) != null;
    }

    /**
     * 查找实例容器中实现指定接口的 Bean 类全限定名。
     * <p>
     * 遍历 Bean 的类层级（含 CGLIB 代理的目标类），返回第一个匹配的 Bean 类名。
     */
    private String findBeanClassNameImplementingInterface(LingInstance instance, Class<?> iface) {
        if (instance.getContainer() == null) return null;
        String ifaceName = iface.getName();
        try {
            Object container = instance.getContainer();
            // 反射获取 Spring ApplicationContext
            java.lang.reflect.Field ctxField = container.getClass().getDeclaredField("context");
            ctxField.setAccessible(true);
            Object appCtx = ctxField.get(container);
            java.lang.reflect.Method getBeanNames = appCtx.getClass().getMethod("getBeanDefinitionNames");
            String[] beanNames = (String[]) getBeanNames.invoke(appCtx);
            for (String beanName : beanNames) {
                try {
                    Object bean = instance.getContainer().getBean(beanName);
                    if (bean == null) continue;
                    // 遍历类层级，处理 CGLIB 代理（@Cacheable/@Transactional 等）
                    Class<?> beanClass = bean.getClass();
                    while (beanClass != null) {
                        for (Class<?> beanIface : beanClass.getInterfaces()) {
                            if (ifaceName.equals(beanIface.getName())) {
                                return beanClass.getName();
                            }
                        }
                        beanClass = beanClass.getSuperclass();
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("Failed to check beans implementing {} in instance version {}",
                    ifaceName, instance.getVersion());
        }
        return null;
    }

    /**
     * 从 FQSID 中提取接口全限定名。
     * <p>
     * FQSID 格式为 "lingId:interfaceFullName"，冒号后即为接口全限定名。
     */
    private String extractInterfaceName(String fqsid) {
        int colon = fqsid.indexOf(':');
        return colon >= 0 ? fqsid.substring(colon + 1) : fqsid;
    }

    /**
     * 判断 FQSID 是否为隐式接口服务。
     * <p>
     * 约定：隐式接口服务的 serviceName 部分是全限定接口名（必然含 "."），
     * 而 @LingService 显式注解的短 ID 是用户自定义标识符（不应含 "."）。
     * <p>
     * ⚠️ 该判定依赖隐式约定。长远应在 LingServiceRegistry 层面提供
     * isExplicitService(fqsid) 查询接口，从注册源头区分服务类型。
     */
    private boolean isInterfaceService(String fqsid) {
        if (fqsid == null) {
            return false;
        }
        int colon = fqsid.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String serviceName = fqsid.substring(colon + 1);
        return serviceName.contains(".");
    }

    /**
     * 真实调用灵元服务方法
     *
     * @param version 目标版本号。为空时走默认实例（稳定版），绕过金丝雀路由；
     *                指定时走对应版本实例，用于金丝雀接口验证。
     */
    public InvokeResultDTO invokeService(String lingId, String fqsid, String methodName,
            String[] parameterTypes, Object[] args, String version) {
        return invokeService(lingId, fqsid, methodName, parameterTypes, args, version, "SPECIFIED");
    }

    /**
     * 真实调用灵元服务方法。
     *
     * @param version     目标版本号（仅 routingMode=SPECIFIED 时生效）
     * @param routingMode 路由模式：SPECIFIED（指定版本，默认）/ PROPORTIONAL（按流量比例随机路由）
     */
    public InvokeResultDTO invokeService(String lingId, String fqsid, String methodName,
            String[] parameterTypes, Object[] args, String version, String routingMode) {
        long start = System.currentTimeMillis();
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        boolean classLoaderChanged = false;
        boolean proportional = "PROPORTIONAL".equalsIgnoreCase(routingMode);
        try {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null) {
                return InvokeResultDTO.builder()
                        .success(false)
                        .error("Ling not found: " + lingId)
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }
            if (!runtime.isAvailable()) {
                return InvokeResultDTO.builder()
                        .success(false)
                        .error("Ling not available: " + lingId)
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }

            // 解析目标实例：按比例路由时按流量比例随机选择，否则指定版本或默认实例
            LingInstance targetInstance;
            String routedVersion = null;
            if (proportional) {
                targetInstance = resolveProportionalInstance(runtime);
                if (targetInstance != null) {
                    routedVersion = targetInstance.getVersion();
                }
            } else {
                targetInstance = resolveTargetInstance(runtime, version);
            }
            if (targetInstance == null) {
                return InvokeResultDTO.builder()
                        .success(false)
                        .error(version != null && !version.isEmpty()
                                ? "Target version not available: " + version
                                : "No available instance")
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }

            ClassLoader classLoader = targetInstance.getClassLoader();
            if (classLoader != null) {
                Thread.currentThread().setContextClassLoader(classLoader);
                classLoaderChanged = true;
            } else {
                classLoader = originalClassLoader;
            }

            Object[] convertedArgs;
            try {
                convertedArgs = ParameterParsingUtils.convertArgs(parameterTypes, args, classLoader, objectMapper);
            } catch (Exception e) {
                log.warn("Parameter conversion failed: {}/{} - {}", fqsid, methodName, e.getMessage());
                return InvokeResultDTO.builder()
                        .success(false)
                        .error("Parameter conversion failed: " + e.getMessage())
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }

            InvocationContext ctx = InvocationContext.obtain();
            try {
                ctx.setTargetLingId(lingId);
                ctx.setCallerLingId("dashboard");
                ctx.setServiceFQSID(fqsid);
                ctx.setMethodName(methodName);
                ctx.setParameterTypeNames(parameterTypes);
                ctx.setArgs(convertedArgs);
                // 不设 SIMULATION → 走真实管线 + 真实业务执行
                ctx.setRuntime(runtime);

                // 显式补全真实的目标实现类名，防止治理过滤器反射解析类名失败。
                // 接口服务的 classCache 可能被多版本覆盖，需按目标实例解析正确的实现类名；
                // 显式注解服务直接用 classCache 中的实现类名。
                String targetClassName = resolveTargetClassName(fqsid, targetInstance);
                if (targetClassName != null && !targetClassName.isEmpty()) {
                    ctx.resolution().setTargetClassName(targetClassName);
                }

                // 预设目标实例，绕过金丝雀路由。
                // 演练场需要可稳定复现的调用环境，不应受金丝雀流量比例波动影响。
                // 指定版本时走对应实例（用于金丝雀验证），否则走默认实例（稳定版）。
                if (!targetInstance.isReady()) {
                    return InvokeResultDTO.builder()
                            .success(false)
                            .error("Target instance not ready: " + targetInstance.getVersion()
                                    + " (status=" + targetInstance.currentStatus() + ")")
                            .durationMs(System.currentTimeMillis() - start)
                            .build();
                }
                ctx.routing().setTargetInstance(targetInstance);

                Object result = pipelineEngine.invoke(ctx);
                long duration = System.currentTimeMillis() - start;

                return InvokeResultDTO.builder()
                        .success(true)
                        .result(result)
                        .durationMs(duration)
                        .routedVersion(routedVersion)
                        .traces(buildTraces(ctx.getTraces()))
                        .build();
            } finally {
                ctx.recycle();
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("Service invocation failed: {}/{} - {}", fqsid, methodName, e.getMessage());

            return InvokeResultDTO.builder()
                    .success(false)
                    .error(e.getMessage())
                    .durationMs(duration)
                    .build();
        } finally {
            if (classLoaderChanged) {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }
    }


    private ServiceMetadataDTO.MethodMetadata parseMethodSignature(String fqsid, String signature) {
        int parenStart = signature.indexOf('(');
        if (parenStart < 0) {
            return ServiceMetadataDTO.MethodMetadata.builder()
                    .name(signature)
                    .signature(signature)
                    .parameterTypes(Collections.emptyList())
                    .returnType("void")
                    .build();
        }

        String name = signature.substring(0, parenStart);
        String paramsStr = signature.substring(parenStart + 1, signature.length() - 1);

        List<String> paramTypes = new ArrayList<>();
        if (!paramsStr.isEmpty()) {
            Collections.addAll(paramTypes, paramsStr.split(","));
        }

        String returnType = lingServiceRegistry.getReturnType(fqsid, signature);
        if (returnType == null) {
            returnType = "void";
        }

        return ServiceMetadataDTO.MethodMetadata.builder()
                .name(name)
                .parameterTypes(paramTypes)
                .returnType(simplifyTypeName(returnType))
                .signature(signature)
                .build();
    }

    private String simplifyTypeName(String typeName) {
        if (typeName == null) {
            return "void";
        }
        int lastDot = typeName.lastIndexOf('.');
        return lastDot >= 0 ? typeName.substring(lastDot + 1) : typeName;
    }

    /**
     * 解析调用目标实现类名。
     * <p>
     * 显式注解服务直接用 classCache 中的实现类名（注册时记录，不会被多版本覆盖，
     * 因为显式服务的 fqsid 通常含版本相关标识或用户自定义 ID）。
     * <p>
     * 接口服务（含 sharedapi）的 classCache 可能被多版本覆盖，不能直接使用。
     * 改为从目标实例容器中查找实现该接口的 Bean，返回其类全限定名。
     * 这样稳定版调用返回稳定版实现类名，金丝雀调用返回金丝雀实现类名。
     *
     * @param fqsid          服务全限定 ID
     * @param targetInstance 目标实例
     * @return 目标实现类全限定名，解析失败时返回 null
     */
    private String resolveTargetClassName(String fqsid, LingInstance targetInstance) {
        // 显式注解服务：直接用 classCache
        if (!isInterfaceService(fqsid)) {
            return lingServiceRegistry.getServiceClassName(fqsid);
        }
        // 接口服务：从目标实例容器中查找实现该接口的 Bean 类名
        ClassLoader cl = targetInstance.getClassLoader();
        if (cl == null) {
            return lingServiceRegistry.getServiceClassName(fqsid);
        }
        String interfaceName = extractInterfaceName(fqsid);
        try {
            Class<?> iface = Class.forName(interfaceName, false, cl);
            String beanClassName = findBeanClassNameImplementingInterface(targetInstance, iface);
            if (beanClassName != null) {
                return beanClassName;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve target class name for interface service {} in version {}",
                    fqsid, targetInstance.getVersion());
        }
        // 兜底：用 classCache 中的值
        return lingServiceRegistry.getServiceClassName(fqsid);
    }

    /**
     * 解析调用目标实例。
     * <p>
     * 指定版本号时优先返回对应版本实例（用于金丝雀接口验证）；
     * 未指定版本时返回默认实例（稳定版），绕过金丝雀路由；
     * 默认实例不存在时退化到首个活跃实例，避免完全不可用。
     *
     * @param runtime 灵元运行时
     * @param version 目标版本号，可为空
     * @return 目标实例，无可用实例时返回 null
     */
    private LingInstance resolveTargetInstance(LingRuntime runtime, String version) {
        if (runtime == null || runtime.getInstancePool() == null) {
            return null;
        }
        // 指定版本时精确匹配
        if (version != null && !version.isEmpty()) {
            LingInstance instance = runtime.getInstancePool().getInstance(version);
            if (instance != null) {
                return instance;
            }
            log.warn("Target version [{}] not found in active pool, fallback to default", version);
        }
        // 默认走稳定版
        LingInstance defaultInst = runtime.getInstancePool().getDefault();
        if (defaultInst != null) {
            return defaultInst;
        }
        // 退化到首个活跃实例
        List<LingInstance> instances = runtime.getInstancePool().getActiveInstances();
        if (instances != null && !instances.isEmpty()) {
            return instances.get(0);
        }
        return null;
    }

    /**
     * 按流量分发比例随机选择目标实例（C2 按比例路由模式）。
     * <p>
     * 读取当前灵元的金丝雀配置（稳定版/金丝雀版比例），按比例随机路由。
     * 只有一个版本时退化为该版本实例。
     */
    private LingInstance resolveProportionalInstance(LingRuntime runtime) {
        if (runtime == null || runtime.getInstancePool() == null) {
            return null;
        }
        List<LingInstance> instances = runtime.getInstancePool().getActiveInstances();
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        // 单版本直接返回
        if (instances.size() == 1) {
            return instances.get(0);
        }
        LingInstance defaultInst = runtime.getInstancePool().getDefault();
        // 读取金丝雀比例
        CanaryRouter.CanaryConfig config = canaryRouter.getCanaryConfig(runtime.getLingId());
        int canaryPercent = config != null ? config.getPercent() : 0;
        // 按比例随机选择
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < canaryPercent) {
            // 路由到金丝雀版
            String canaryVersion = config != null ? config.getCanaryVersion() : null;
            if (canaryVersion != null) {
                LingInstance canary = runtime.getInstancePool().getInstance(canaryVersion);
                if (canary != null) {
                    return canary;
                }
            }
            // 金丝雀版本未找到，退化为非默认实例
            for (LingInstance inst : instances) {
                if (!inst.equals(defaultInst)) {
                    return inst;
                }
            }
        }
        // 路由到稳定版
        return defaultInst != null ? defaultInst : instances.get(0);
    }

    /**
     * 检查方法在指定 ClassLoader 视角下是否真实可用。
     * <p>
     * 服务注册表会累积多版本方法，但路由器只会选择默认实例（稳定版），
     * 因此需要过滤掉金丝雀独有方法，避免演练场展示无法调用的方法。
     */
    private boolean isMethodAvailable(String className, ServiceMetadataDTO.MethodMetadata method,
            ClassLoader classLoader) {
        if (classLoader == null || className == null || className.isEmpty()) {
            // 无法验证时保留方法，向后兼容
            return true;
        }
        try {
            Class<?> targetClass = Class.forName(className, false, classLoader);
            List<String> paramTypeNames = method.getParameterTypes();
            Class<?>[] paramTypes = new Class<?>[paramTypeNames.size()];
            for (int i = 0; i < paramTypeNames.size(); i++) {
                String typeName = paramTypeNames.get(i).trim();
                Class<?> primitive = ParameterParsingUtils.resolvePrimitiveType(typeName);
                paramTypes[i] = primitive != null ? primitive : Class.forName(typeName, false, classLoader);
            }
            targetClass.getMethod(method.getName(), paramTypes);
            return true;
        } catch (Exception e) {
            log.debug("Method {} not available on class {} via default ClassLoader, filtered out",
                    method.getName(), className);
            return false;
        }
    }



    private List<InvokeResultDTO.TraceEntry> buildTraces(List<EngineTrace> engineTraces) {
        if (engineTraces == null) {
            return Collections.emptyList();
        }
        return engineTraces.stream()
                .map(t -> InvokeResultDTO.TraceEntry.builder()
                        .source(t.getSource())
                        .action(t.getAction())
                        .type(t.getType())
                        .build())
                .collect(Collectors.toList());
    }
}
