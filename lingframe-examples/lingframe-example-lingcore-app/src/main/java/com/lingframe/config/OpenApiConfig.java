package com.lingframe.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}