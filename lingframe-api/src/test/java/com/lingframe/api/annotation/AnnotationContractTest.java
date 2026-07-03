package com.lingframe.api.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注解契约测试。
 * <p>
 * 验证框架核心注解的保留策略、目标类型和默认值契约，
 * 确保注解在运行时可反射获取且默认值符合文档描述。
 */
@DisplayName("注解契约测试")
class AnnotationContractTest {

    @Nested
    @DisplayName("@LingService 契约")
    class LingServiceContract {

        @Test
        @DisplayName("RUNTIME 保留策略，确保反射可获取")
        void runtimeRetention() {
            Retention retention = LingService.class.getAnnotation(Retention.class);
            assertNotNull(retention);
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("目标为 METHOD")
        void methodTarget() {
            Target target = LingService.class.getAnnotation(Target.class);
            assertNotNull(target);
            assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD));
        }

        @Test
        @DisplayName("默认 timeout 为 3000ms")
        void defaultTimeout() throws NoSuchMethodException {
            Method dummy = LingServiceContract.class.getDeclaredMethod("dummyLingService");
            LingService anno = dummy.getAnnotation(LingService.class);
            assertEquals(3000, anno.timeout());
        }

        @Test
        @DisplayName("默认 desc 为空字符串")
        void defaultDesc() throws NoSuchMethodException {
            Method dummy = LingServiceContract.class.getDeclaredMethod("dummyLingService");
            LingService anno = dummy.getAnnotation(LingService.class);
            assertEquals("", anno.desc());
        }

        @LingService(id = "test-svc")
        @SuppressWarnings("unused")
        private void dummyLingService() {
        }
    }

    @Nested
    @DisplayName("@LingReference 契约")
    class LingReferenceContract {

        @Test
        @DisplayName("RUNTIME 保留策略")
        void runtimeRetention() {
            Retention retention = LingReference.class.getAnnotation(Retention.class);
            assertNotNull(retention);
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("目标为 FIELD")
        void fieldTarget() {
            Target target = LingReference.class.getAnnotation(Target.class);
            assertNotNull(target);
            assertTrue(Arrays.asList(target.value()).contains(ElementType.FIELD));
        }

        @Test
        @DisplayName("默认 lingId 为空字符串")
        void defaultLingId() throws NoSuchFieldException {
            LingReference anno = LingReferenceContract.class.getDeclaredField("dummyRef").getAnnotation(LingReference.class);
            assertEquals("", anno.lingId());
        }

        @Test
        @DisplayName("默认 timeout 为 3000ms")
        void defaultTimeout() throws NoSuchFieldException {
            LingReference anno = LingReferenceContract.class.getDeclaredField("dummyRef").getAnnotation(LingReference.class);
            assertEquals(3000, anno.timeout());
        }

        @Test
        @DisplayName("默认 fallback 为 void.class")
        void defaultFallback() throws NoSuchFieldException {
            LingReference anno = LingReferenceContract.class.getDeclaredField("dummyRef").getAnnotation(LingReference.class);
            assertEquals(void.class, anno.fallback());
        }

        @LingReference
        @SuppressWarnings("unused")
        private Object dummyRef;
    }

    @Nested
    @DisplayName("@RequiresPermission 契约")
    class RequiresPermissionContract {

        @Test
        @DisplayName("RUNTIME 保留策略")
        void runtimeRetention() {
            Retention retention = RequiresPermission.class.getAnnotation(Retention.class);
            assertNotNull(retention);
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("目标为 METHOD 和 TYPE")
        void methodAndTypeTarget() {
            Target target = RequiresPermission.class.getAnnotation(Target.class);
            assertNotNull(target);
            List<ElementType> targets = Arrays.asList(target.value());
            assertTrue(targets.contains(ElementType.METHOD));
            assertTrue(targets.contains(ElementType.TYPE));
        }

        @Test
        @DisplayName("默认 description 为空字符串")
        void defaultDescription() throws NoSuchMethodException {
            Method dummy = RequiresPermissionContract.class.getDeclaredMethod("dummyPermMethod");
            RequiresPermission anno = dummy.getAnnotation(RequiresPermission.class);
            assertEquals("", anno.description());
        }

        @RequiresPermission("user:export")
        @SuppressWarnings("unused")
        private void dummyPermMethod() {
        }
    }

    @Nested
    @DisplayName("@Auditable 契约")
    class AuditableContract {

        @Test
        @DisplayName("RUNTIME 保留策略")
        void runtimeRetention() {
            Retention retention = Auditable.class.getAnnotation(Retention.class);
            assertNotNull(retention);
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("目标为 METHOD")
        void methodTarget() {
            Target target = Auditable.class.getAnnotation(Target.class);
            assertNotNull(target);
            assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD));
        }

        @Test
        @DisplayName("默认 resource 为空字符串")
        void defaultResource() throws NoSuchMethodException {
            Method dummy = AuditableContract.class.getDeclaredMethod("dummyAuditableMethod");
            Auditable anno = dummy.getAnnotation(Auditable.class);
            assertEquals("", anno.resource());
        }

        @Auditable(action = "Export")
        @SuppressWarnings("unused")
        private void dummyAuditableMethod() {
        }
    }

    private static void assertNotNull(Object obj) {
        org.junit.jupiter.api.Assertions.assertNotNull(obj);
    }
}
