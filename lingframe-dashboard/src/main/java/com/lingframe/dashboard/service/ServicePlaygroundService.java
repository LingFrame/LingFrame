package com.lingframe.dashboard.service;

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
import java.util.List;
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

    /**
     * 获取指定灵元的所有服务元数据
     */
    public List<ServiceMetadataDTO> getServices(String lingId) {
        List<String> fqsidList = lingServiceRegistry.getServicesByLingId(lingId);
        if (fqsidList == null || fqsidList.isEmpty()) {
            return Collections.emptyList();
        }

        return fqsidList.stream().map(fqsid -> {
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
    }

    /**
     * 真实调用灵元服务方法
     */
    public InvokeResultDTO invokeService(String lingId, String fqsid, String methodName,
                                          String[] parameterTypes, Object[] args) {
        long start = System.currentTimeMillis();
        try {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null) {
                return InvokeResultDTO.builder()
                        .success(false)
                        .error("灵元不存在: " + lingId)
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }
            if (!runtime.isAvailable()) {
                return InvokeResultDTO.builder()
                        .success(false)
                        .error("灵元不可用: " + lingId)
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
                ctx.setArgs(args);
                // 不设 SIMULATION → 走真实管线 + 真实业务执行
                ctx.setRuntime(runtime);

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
        }
    }

    /**
     * 解析方法签名为结构化元数据
     * <p>
     * 签名格式：methodName(paramType1,paramType2)
     */
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

    /**
     * 简化类型名：java.lang.String → String
     */
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
