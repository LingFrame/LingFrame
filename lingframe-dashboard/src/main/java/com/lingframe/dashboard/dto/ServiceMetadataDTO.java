package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 灵元服务元数据 DTO
 * <p>
 * 用于前端服务演练场展示灵元注册的服务列表及方法签名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceMetadataDTO {

    /** 服务 FQSID，如 "greeting:GreetingService" */
    private String fqsid;

    /** 实现类全限定名 */
    private String className;

    /** 方法列表 */
    private List<MethodMetadata> methods;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MethodMetadata {
        /** 方法名 */
        private String name;
        /** 参数类型列表 */
        private List<String> parameterTypes;
        /** 返回类型 */
        private String returnType;
        /** 完整签名，如 "sayHello(java.lang.String)" */
        private String signature;
    }
}
