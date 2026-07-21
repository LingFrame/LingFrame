package com.lingframe.starter.adapter;

import com.lingframe.starter.web.LingWebMetadataExtractor;
import com.lingframe.starter.web.WebInterfaceMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("灵元 Web 元数据提取与控制器注册契约")
class SpringLingContainerControllerRegistrationTest {

    @Test
    @DisplayName("应展开映射组合并保留请求条件")
    void shouldExpandMappingCombinationsAndPreserveConditions() {
        MultiMappingController bean = new MultiMappingController();
        LingWebMetadataExtractor extractor = new LingWebMetadataExtractor(
                "v1", MultiMappingController.class.getClassLoader(), null);

        List<WebInterfaceMetadata> captured = extractor.extractFromController(
                "ling-a", "multiMappingController", bean, MultiMappingController.class);

        assertEquals(8, captured.size());
        Set<String> routes = captured.stream()
                .map(metadata -> metadata.getHttpMethod() + " " + metadata.getUrlPattern())
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList(
                "GET /ling-a/api/v1",
                "POST /ling-a/api/v1",
                "GET /ling-a/api/v2",
                "POST /ling-a/api/v2",
                "GET /ling-a/alt/v1",
                "POST /ling-a/alt/v1",
                "GET /ling-a/alt/v2",
                "POST /ling-a/alt/v2")), routes);

        for (WebInterfaceMetadata metadata : captured) {
            assertEquals("multiMappingController", metadata.getTargetBeanName());
            assertEquals(MultiMappingController.class.getName(), metadata.getTargetClassName());
            assertEquals("upsert", metadata.getTargetMethodName());
            assertArrayEquals(new String[0], metadata.getTargetMethodParameterTypeNames());
            assertEquals("v1", metadata.getVersion());
            assertSame(bean, metadata.getTargetBean());
            assertArrayEquals(new String[]{"mode=full"}, metadata.getParams());
            assertArrayEquals(new String[]{"X-Test=1"}, metadata.getHeaders());
            assertArrayEquals(new String[]{"application/json"}, metadata.getConsumes());
            assertArrayEquals(new String[]{"application/json"}, metadata.getProduces());
            assertNotNull(metadata.getRequestMappingInfo());
            if ("POST".equals(metadata.getHttpMethod())) {
                assertTrue(metadata.isShouldAudit());
                assertTrue(metadata.getAuditAction().startsWith("POST /ling-a/"));
            } else {
                assertFalse(metadata.isShouldAudit());
            }
        }
    }

    @Test
    @DisplayName("应为继承方法记录真实 Controller 类名")
    void shouldCaptureConcreteControllerClassForInheritedMethod() {
        InheritedController bean = new InheritedController();
        LingWebMetadataExtractor extractor = new LingWebMetadataExtractor(
                "v1", InheritedController.class.getClassLoader(), null);

        List<WebInterfaceMetadata> captured = extractor.extractFromController(
                "ling-a", "inheritedController", bean, InheritedController.class);

        assertEquals(1, captured.size());
        WebInterfaceMetadata metadata = captured.get(0);
        assertEquals(InheritedController.class.getName(), metadata.getTargetClassName());
        assertEquals(InheritedBaseController.class.getName(),
                metadata.getTargetMethod().getDeclaringClass().getName());
    }

    @Test
    @DisplayName("注册时应预提取类级 @Tag 与方法 @Operation 合并为文档元数据")
    void shouldPreExtractClassTagAndOperationForOpenApi() {
        TaggedController bean = new TaggedController();
        LingWebMetadataExtractor extractor = new LingWebMetadataExtractor(
                "1.0.0", TaggedController.class.getClassLoader(), null);

        List<WebInterfaceMetadata> captured = extractor.extractFromController(
                "user-ling", "taggedController", bean, TaggedController.class);

        assertEquals(1, captured.size());
        WebInterfaceMetadata metadata = captured.get(0);
        assertEquals("list users", metadata.getOpSummary());
        assertArrayEquals(new String[]{"用户"}, metadata.getOpTags());
        assertEquals("用户模块", metadata.getOpTagDescription());
    }

    @RestController
    @RequestMapping(path = {"/api", "/alt"})
    static class MultiMappingController {

        @RequestMapping(
                path = {"/v1", "/v2"},
                method = {RequestMethod.GET, RequestMethod.POST},
                consumes = {"application/json"},
                produces = {"application/json"},
                params = {"mode=full"},
                headers = {"X-Test=1"})
        public String upsert() {
            return "ok";
        }
    }

    static class InheritedBaseController {
        @RequestMapping(path = "/detail", method = RequestMethod.GET)
        public String detail() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping(path = "/inherited")
    static class InheritedController extends InheritedBaseController {
    }

    /**
     * 模拟老项目迁移：类级 @Tag + 方法 @Operation(summary)，无 Operation.tags。
     */
    @io.swagger.v3.oas.annotations.tags.Tag(name = "用户", description = "用户模块")
    @RestController
    @RequestMapping(path = "/users")
    static class TaggedController {

        @io.swagger.v3.oas.annotations.Operation(summary = "list users")
        @RequestMapping(path = "/list", method = RequestMethod.GET)
        public String list() {
            return "ok";
        }
    }
}
