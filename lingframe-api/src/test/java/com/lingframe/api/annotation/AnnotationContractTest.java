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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        @DisplayName("目标为 METHOD 和 TYPE")
        void methodAndTypeTarget() {
            Target target = LingService.class.getAnnotation(Target.class);
            assertNotNull(target);
            List<ElementType> targets = Arrays.asList(target.value());
            assertTrue(targets.contains(ElementType.METHOD));
            assertTrue(targets.contains(ElementType.TYPE));
        }

        @Test
        @DisplayName("默认 id 为空字符串（由灵核推导短 ID）")
        void defaultId() throws NoSuchMethodException {
            Method dummy = LingServiceContract.class.getDeclaredMethod("dummyDefaultId");
            LingService anno = dummy.getAnnotation(LingService.class);
            assertEquals("", anno.id());
        }

        @Test
        @DisplayName("默认 desc 为空字符串")
        void defaultDesc() throws NoSuchMethodException {
            Method dummy = LingServiceContract.class.getDeclaredMethod("dummyLingService");
            LingService anno = dummy.getAnnotation(LingService.class);
            assertEquals("", anno.desc());
        }

        @Test
        @DisplayName("显式 id 可标注在 METHOD 上")
        void explicitIdOnMethod() throws NoSuchMethodException {
            Method dummy = LingServiceContract.class.getDeclaredMethod("dummyLingService");
            LingService anno = dummy.getAnnotation(LingService.class);
            assertEquals("test-svc", anno.id());
        }

        @Test
        @DisplayName("可标注在 TYPE 上")
        void canAnnotateOnType() {
            // 仅验证 @Target 已包含 TYPE；编译期已强制，这里反射断言目标包含 TYPE
            Target target = LingService.class.getAnnotation(Target.class);
            assertTrue(Arrays.asList(target.value()).contains(ElementType.TYPE));
        }

        @LingService(id = "test-svc")
        @SuppressWarnings("unused")
        private void dummyLingService() {
        }

        @LingService
        @SuppressWarnings("unused")
        private void dummyDefaultId() {
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
        @DisplayName("默认 serviceId 为空字符串（仅按类型路由）")
        void defaultServiceId() throws NoSuchFieldException {
            LingReference anno = LingReferenceContract.class.getDeclaredField("dummyRef").getAnnotation(LingReference.class);
            assertEquals("", anno.serviceId());
        }

        @Test
        @DisplayName("显式 serviceId 可锚定短 ID 或 FQSID")
        void explicitServiceId() throws NoSuchFieldException {
            LingReference anno = LingReferenceContract.class.getDeclaredField("anchoredRef").getAnnotation(LingReference.class);
            assertEquals("lingcore-app:authService", anno.serviceId());
        }

        @Test
        @DisplayName("显式 lingId 可限定灵元")
        void explicitLingId() throws NoSuchFieldException {
            LingReference anno = LingReferenceContract.class.getDeclaredField("anchoredRef").getAnnotation(LingReference.class);
            assertEquals("user-ling", anno.lingId());
        }

        @LingReference
        @SuppressWarnings("unused")
        private Object dummyRef;

        @LingReference(lingId = "user-ling", serviceId = "lingcore-app:authService")
        @SuppressWarnings("unused")
        private Object anchoredRef;
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
}
