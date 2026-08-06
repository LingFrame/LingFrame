package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@DisplayName("LingScanCachePurger 有界清理")
class LingScanCachePurgerTest {

    @Test
    @DisplayName("null ClassLoader 时 no-op")
    void shouldNoOpWhenClassLoaderNull() {
        assertDoesNotThrow(() ->
                LingScanCachePurger.purgeAnnotationCachesAfterMetadataExtract("x", null));
    }

    @Test
    @DisplayName("扫描后 purge 不抛异常")
    void shouldPurgeAfterSyntheticScanWithoutThrowing() {
        ClassLoader cl = SampleController.class.getClassLoader();
        // 写入与扫描相同路径的静态缓存
        AnnotatedElementUtils.findMergedAnnotation(SampleController.class, RequestMapping.class);
        AnnotatedElementUtils.findMergedAnnotation(
                SampleController.class.getMethods()[0], GetMapping.class);

        assertDoesNotThrow(() ->
                LingScanCachePurger.purgeAnnotationCachesAfterMetadataExtract("sample-ling", cl));
    }

    @RestController
    @RequestMapping("/sample")
    static class SampleController {
        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }
}
