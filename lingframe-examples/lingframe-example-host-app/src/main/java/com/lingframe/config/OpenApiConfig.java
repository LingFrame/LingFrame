package com.lingframe.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 配置
 * 使用 GroupedOpenApi 正确实现 API 分组
 */
@Configuration
public class OpenApiConfig {

    // 主 API 配置
    @Bean
    public OpenAPI lingFrameOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LingFrame Example API")
                        .description("LingFrame 示例应用 API 文档")
                        .version("1.0"));
    }

    // Core (Dashboard) API 分组
    @Bean
    public GroupedOpenApi coreApi() {
        return GroupedOpenApi.builder()
                .group("01-core")
                .displayName("01. Core (Dashboard)")
                .pathsToMatch("/**/dashboard/**")
                .build();
    }

    // Host Application API 分组
    @Bean
    public GroupedOpenApi hostApi() {
        return GroupedOpenApi.builder()
                .group("02-host")
                .displayName("02. Host Application")
                .pathsToExclude("/**/dashboard/**", "/*-plugin/**")
                .build();
    }

    // Plugins API 分组 - 匹配所有插件路径
    @Bean
    public GroupedOpenApi pluginApi() {
        return GroupedOpenApi.builder()
                .group("03-plugins")
                .displayName("03. Plugins")
                .pathsToMatch("/**-plugin/**")
                .build();
    }

    // 完整 API（所有接口）
    @Bean
    public GroupedOpenApi allApi() {
        return GroupedOpenApi.builder()
                .group("00-all")
                .displayName("00. All APIs")
                .pathsToMatch("/**")
                .build();
    }
}