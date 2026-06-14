package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.dashboard.dto.InvokeResultDTO;
import com.lingframe.dashboard.dto.ServiceMetadataDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 获取指定灵元的所有服务元数据
     */
    public List<ServiceMetadataDTO> getServices(String lingId) {
        List<String> fqsidList = lingServiceRegistry.getServicesByLingId(lingId);
        if (fqsidList == null || fqsidList.isEmpty()) {
            return Collections.emptyList();
        }

        List<ServiceMetadataDTO> allDtos = fqsidList.stream().map(fqsid -> {
            String className = lingServiceRegistry.getServiceClassName(fqsid);
            List<String> methodSignatures = lingServiceRegistry.getProviderMethods(fqsid);

            List<ServiceMetadataDTO.MethodMetadata> methods = methodSignatures.stream()
                    .map(sig -> parseMethodSignature(fqsid, sig))
                    .collect(Collectors.toList());

            return ServiceMetadataDTO.builder()
                    .fqsid(fqsid)
                    .className(className)
                    .methods(methods)
                    .build();
        }).collect(Collectors.toList());

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
     */
    public InvokeResultDTO invokeService(String lingId, String fqsid, String methodName,
            String[] parameterTypes, Object[] args) {
        long start = System.currentTimeMillis();
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        boolean classLoaderChanged = false;
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

            ClassLoader classLoader = null;
            if (runtime.getInstancePool() != null) {
                List<com.lingframe.core.ling.LingInstance> instances = runtime.getInstancePool().getActiveInstances();
                if (instances != null && !instances.isEmpty()) {
                    classLoader = instances.get(0).getClassLoader();
                }
            }
            if (classLoader != null) {
                Thread.currentThread().setContextClassLoader(classLoader);
                classLoaderChanged = true;
            } else {
                classLoader = originalClassLoader;
            }

            Object[] convertedArgs;
            try {
                convertedArgs = convertArgs(parameterTypes, args, classLoader);
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

                // 显式补全真实的目标实现类名，防止治理过滤器反射解析类名失败
                String targetClassName = lingServiceRegistry.getServiceClassName(fqsid);
                if (targetClassName != null && !targetClassName.isEmpty()) {
                    ctx.resolution().setTargetClassName(targetClassName);
                }

                Object result = pipelineEngine.invoke(ctx);
                long duration = System.currentTimeMillis() - start;

                return InvokeResultDTO.builder()
                        .success(true)
                        .result(result)
                        .durationMs(duration)
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

    private Object[] convertArgs(String[] parameterTypes, Object[] args, ClassLoader classLoader) throws Exception {
        if (parameterTypes == null || args == null || parameterTypes.length == 0) {
            return args;
        }
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (i >= parameterTypes.length) {
                converted[i] = args[i];
                continue;
            }
            String typeName = parameterTypes[i];
            Object value = args[i];
            if (value == null) {
                converted[i] = null;
                continue;
            }
            try {
                Class<?> targetClass = resolveClass(typeName, classLoader);
                converted[i] = convertValue(value, targetClass);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "arg[" + i + "] (" + typeName + "): " + e.getMessage(), e);
            }
        }
        return converted;
    }

    private Class<?> resolveClass(String typeName, ClassLoader classLoader) throws ClassNotFoundException {
        switch (typeName) {
            case "int": return int.class;
            case "long": return long.class;
            case "double": return double.class;
            case "float": return float.class;
            case "boolean": return boolean.class;
            case "char": return char.class;
            case "byte": return byte.class;
            case "short": return short.class;
            default:
                if (classLoader != null) {
                    return Class.forName(typeName, true, classLoader);
                } else {
                    return Class.forName(typeName);
                }
        }
    }

    private Object convertValue(Object value, Class<?> targetClass) {
        if (value == null) {
            return null;
        }
        if (targetClass.isInstance(value)) {
            return value;
        }
        if (targetClass == int.class || targetClass == Integer.class) {
            if (value instanceof Number) return ((Number) value).intValue();
            return Integer.parseInt(value.toString());
        }
        if (targetClass == long.class || targetClass == Long.class) {
            if (value instanceof Number) return ((Number) value).longValue();
            return Long.parseLong(value.toString());
        }
        if (targetClass == double.class || targetClass == Double.class) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            return Double.parseDouble(value.toString());
        }
        if (targetClass == float.class || targetClass == Float.class) {
            if (value instanceof Number) return ((Number) value).floatValue();
            return Float.parseFloat(value.toString());
        }
        if (targetClass == boolean.class || targetClass == Boolean.class) {
            return Boolean.parseBoolean(value.toString());
        }
        try {
            return objectMapper.convertValue(value, targetClass);
        } catch (Exception e) {
            log.warn("Failed to convert value {} to target class {}, fallback to raw value", value, targetClass.getName(), e);
            return value;
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
