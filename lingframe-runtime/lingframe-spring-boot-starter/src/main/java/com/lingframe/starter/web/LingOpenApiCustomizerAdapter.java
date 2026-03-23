package com.lingframe.starter.web;

import java.util.Collection;

import io.swagger.v3.oas.models.OpenAPI;

/**
 * 灵元 OpenAPI 自定义器桥接接口。
 * <p>
 * 用于屏蔽 SpringDoc v1 (OpenApiCustomiser) 与 v2 (OpenApiCustomizer) 的 API 差异。
 * </p>
 */
@FunctionalInterface
public interface LingOpenApiCustomizerAdapter {
    /**
     * 自定义 OpenAPI 对象。
     * @param openApi OpenAPI 实例
     */
    void customise(OpenAPI openApi);

    /**
     * 自定义 OpenAPI 对象，并支持路径过滤。
     */
    default void customise(OpenAPI openApi, Collection<String> pathsToMatch) {
        customise(openApi);
    }

    /**
     * 自定义 OpenAPI 对象，并支持全量分组规则过滤。
     * @param openApi OpenAPI 实例
     * @param pathsToMatch 路径匹配规则
     * @param packagesToScan 包扫描规则
     * @param pathsToExclude 路径排除规则
     * @param packagesToExclude 包排除规则
     */
    default void customise(OpenAPI openApi, 
                          Collection<String> pathsToMatch, Collection<String> packagesToScan,
                          Collection<String> pathsToExclude, Collection<String> packagesToExclude) {
        customise(openApi, pathsToMatch);
    }
}
