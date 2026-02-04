package com.lingframe.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.stream.Collectors;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

@Configuration
public class OpenApiConfig {

    // 主 API 配置
    @Bean
    @Primary
    public OpenAPI lingFrameOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LingFrame Example API")
                        .description("LingFrame 示例应用 API 文档")
                        .version("1.0"));
    }

    // Core (Dashboard) API 分组
    @Bean
    public OpenAPI coreOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("01. Core (Dashboard)")
                        .description("Dashboard 核心功能 API")
                        .version("1.0"));
    }

    // Host Application API 分组
    @Bean
    public OpenAPI hostOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("02. Host Application")
                        .description("主应用 API")
                        .version("1.0"));
    }

    // Plugins API 分组
    @Bean
    public OpenAPI pluginOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("03. Plugins")
                        .description("插件系统 API")
                        .version("1.0"));
    }

    // Core (Dashboard) 路径过滤器
    @Bean
    public OpenApiCustomiser corePathsCustomiser() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            // 创建新的 Paths 对象
            Paths filteredPaths = new Paths();

            // 过滤只包含 dashboard 路径的接口
            openApi.getPaths().forEach((path, pathItem) -> {
                if (path.contains("/dashboard/")) {
                    filteredPaths.addPathItem(path, pathItem);
                }
            });

            openApi.setPaths(filteredPaths);

            // 同时需要过滤对应的 schemas
            filterSchemas(openApi, filteredPaths);
        };
    }

    // Host Application 路径过滤器
    @Bean
    public OpenApiCustomiser hostPathsCustomiser() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            // 创建新的 Paths 对象
            Paths filteredPaths = new Paths();

            // 过滤路径：包含 com.lingframe 但不包含 dashboard 和 plugin
            openApi.getPaths().forEach((path, pathItem) -> {
                if (!path.contains("/dashboard/") && !path.matches("/[^/]+-plugin/.*")) {
                    filteredPaths.addPathItem(path, pathItem);
                }
            });

            openApi.setPaths(filteredPaths);

            // 同时需要过滤对应的 schemas
            filterSchemas(openApi, filteredPaths);
        };
    }

    // Plugins 路径过滤器
    @Bean
    public OpenApiCustomiser pluginPathsCustomiser() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            // 创建新的 Paths 对象
            Paths filteredPaths = new Paths();

            // 过滤只包含 plugin 路径的接口
            openApi.getPaths().forEach((path, pathItem) -> {
                if (path.matches("/[^/]+-plugin/.*")) {
                    filteredPaths.addPathItem(path, pathItem);
                }
            });

            openApi.setPaths(filteredPaths);

            // 同时需要过滤对应的 schemas
            filterSchemas(openApi, filteredPaths);
        };
    }

    // 辅助方法：根据路径过滤 schemas
    private void filterSchemas(OpenAPI openApi, Paths filteredPaths) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null || filteredPaths.isEmpty()) {
            return;
        }

        // 收集过滤后路径中使用的 schema 名称
        List<String> usedSchemas = new ArrayList<>();

        filteredPaths.forEach((path, pathItem) -> {
            pathItem.readOperations().forEach(operation -> {
                operation.getResponses().forEach((responseCode, response) -> {
                    if (response.getContent() != null) {
                        response.getContent().forEach((mediaTypeName, mediaType) -> {
                            if (mediaType.getSchema() != null) {
                                extractSchemaRefs(mediaType.getSchema(), usedSchemas);
                            }
                        });
                    }
                });

                if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                    operation.getRequestBody().getContent().forEach((mediaTypeName, mediaType) -> {
                        if (mediaType.getSchema() != null) {
                            extractSchemaRefs(mediaType.getSchema(), usedSchemas);
                        }
                    });
                }

                // 处理参数中的 schema
                if (operation.getParameters() != null) {
                    operation.getParameters().forEach(parameter -> {
                        if (parameter.getSchema() != null) {
                            extractSchemaRefs(parameter.getSchema(), usedSchemas);
                        }
                    });
                }
            });
        });

        // 过滤 schemas
        Map<String, io.swagger.v3.oas.models.media.Schema> schemas = openApi.getComponents().getSchemas();
        Map<String, io.swagger.v3.oas.models.media.Schema> filteredSchemas = schemas.entrySet().stream()
                .filter(entry -> usedSchemas.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        openApi.getComponents().setSchemas(filteredSchemas);
    }

    // 辅助方法：提取 schema 引用
    private void extractSchemaRefs(io.swagger.v3.oas.models.media.Schema schema, List<String> schemaRefs) {
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            if (ref.startsWith("#/components/schemas/")) {
                String schemaName = ref.substring("#/components/schemas/".length());
                if (!schemaRefs.contains(schemaName)) {
                    schemaRefs.add(schemaName);
                }
            }
        }

        // 处理数组类型的 schema
        if (schema.getItems() != null) {
            extractSchemaRefs(schema.getItems(), schemaRefs);
        }

        // 处理 properties
        if (schema.getProperties() != null) {
            schema.getProperties().forEach((propName, propSchema) ->
                    extractSchemaRefs((io.swagger.v3.oas.models.media.Schema) propSchema, schemaRefs));
        }

        // 处理 additionalProperties
        if (schema.getAdditionalProperties() instanceof io.swagger.v3.oas.models.media.Schema) {
            extractSchemaRefs((io.swagger.v3.oas.models.media.Schema) schema.getAdditionalProperties(), schemaRefs);
        }

        // 处理 allOf, anyOf, oneOf
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(subSchema -> extractSchemaRefs((Schema) subSchema, schemaRefs));
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(subSchema -> extractSchemaRefs((Schema) subSchema, schemaRefs));
        }
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(subSchema -> extractSchemaRefs((Schema) subSchema, schemaRefs));
        }
    }
}