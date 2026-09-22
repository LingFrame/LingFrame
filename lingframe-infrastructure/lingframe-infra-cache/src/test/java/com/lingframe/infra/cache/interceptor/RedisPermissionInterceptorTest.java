package com.lingframe.infra.cache.interceptor;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RedisPermissionInterceptor 测试")
class RedisPermissionInterceptorTest {

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
    }

    @Nested
    @DisplayName("权限推导")
    class PermissionInferenceTests {

        @Test
        @DisplayName("get 前缀方法应优先按 key pattern 的 READ 权限鉴权")
        void shouldTreatGetPrefixAsReadWithKeyPatternCapability() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.READ)).thenReturn(true);
            MethodInvocation invocation = mockInvocation(method("getValue", String.class), "ok", "user:1");

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            assertEquals("ok", interceptor.invoke(invocation));
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:redis:key:user:*", "getValue", true);
        }

        @Test
        @DisplayName("细粒度 key pattern 被拒绝时应直接拒绝，不回退通用 cache:redis 权限")
        void shouldDenyDirectlyWhenFineGrainedPatternRejected() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE)).thenReturn(false);
            MethodInvocation invocation = mockInvocation(method("deleteValue", String.class), 1L, "user:1");

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class, () -> interceptor.invoke(invocation));
            assertEquals("Ling [ling-a] denied access to Redis operation: deleteValue", ex.getMessage());
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE);
            // 细粒度命中后不应再查询通用权限
            verify(permissionService, never()).isAllowed("ling-a", "cache:redis", AccessType.WRITE);
            // 审计 capability 为细粒度 pattern
            verify(permissionService).audit("ling-a", "cache:redis:key:user:*", "deleteValue", false);
            verify(invocation, never()).proceed();
        }

        @Test
        @DisplayName("细粒度拒绝 + 通用允许 → 仍应拒绝（避免细粒度显式拒绝被通用允许覆盖）")
        void shouldDenyWhenFineGrainedRejectedEvenIfGenericAllowed() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE)).thenReturn(false);
            // 通用权限即使允许，也不应覆盖细粒度显式拒绝
            when(permissionService.isAllowed("ling-a", "cache:redis", AccessType.WRITE)).thenReturn(true);
            MethodInvocation invocation = mockInvocation(method("deleteValue", String.class), 1L, "user:1");

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class, () -> interceptor.invoke(invocation));
            assertEquals("Ling [ling-a] denied access to Redis operation: deleteValue", ex.getMessage());
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE);
            // 细粒度命中后不应查询通用权限，因此通用允许不会生效
            verify(permissionService, never()).isAllowed("ling-a", "cache:redis", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:redis:key:user:*", "deleteValue", false);
            verify(invocation, never()).proceed();
        }

        @Test
        @DisplayName("多 key 操作应要求所有 key pattern capability 均被允许")
        void shouldRequireAllKeyPatternsForMultiKeyOperation() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE)).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:order:*", AccessType.WRITE)).thenReturn(false);
            MethodInvocation invocation = mockInvocation(
                    method("deleteValues", Iterable.class),
                    2L,
                    Arrays.asList("user:1", "order:2"));

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class, () -> interceptor.invoke(invocation));
            assertEquals("Ling [ling-a] denied access to Redis operation: deleteValues", ex.getMessage());
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE);
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:order:*", AccessType.WRITE);
            // 任一细粒度 pattern 被拒绝即直接拒绝，不回退通用权限
            verify(permissionService, never()).isAllowed("ling-a", "cache:redis", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:redis:key:user:*, cache:redis:key:order:*", "deleteValues", false);
            verify(invocation, never()).proceed();
        }

        @Test
        @DisplayName("无 key 参数时应回退到通用 cache:redis 权限")
        void shouldFallbackToGenericCapabilityWhenNoKeyPattern() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis", AccessType.READ)).thenReturn(false);
            // size 无参数，无法推断 key pattern，回退到通用 cache:redis
            MethodInvocation invocation = mockInvocation(method("size"), 1L);

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class, () -> interceptor.invoke(invocation));
            assertEquals("Ling [ling-a] denied access to Redis operation: size", ex.getMessage());
            verify(permissionService).isAllowed("ling-a", "cache:redis", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:redis", "size", false);
            verify(invocation, never()).proceed();
        }

        @Test
        @DisplayName("多 key 操作全部命中时应审计所有具体 key pattern")
        void shouldAuditAllSpecificPatternsWhenMultiKeyOperationIsAllowed() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE)).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:order:*", AccessType.WRITE)).thenReturn(true);
            MethodInvocation invocation = mockInvocation(
                    method("deleteValues", Iterable.class),
                    2L,
                    Arrays.asList("user:1", "order:2"));

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            assertEquals(2L, interceptor.invoke(invocation));
            verify(permissionService).audit(
                    "ling-a",
                    "cache:redis:key:user:*, cache:redis:key:order:*",
                    "deleteValues",
                    true);
        }

        @Test
        @DisplayName("getAndSet 等原子读改写方法应按 WRITE 鉴权（精确匹配，不被 get 前缀误判为 READ）")
        void shouldTreatAtomicReadModifyWriteMethodsAsWrite() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE)).thenReturn(true);
            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            // getAndSet 是 getAnd* 前缀，但必须按 WRITE 鉴权而非 READ
            MethodInvocation invocation = mockInvocation(method("getAndSet", String.class, String.class), "old", "user:1", "new");
            assertEquals("old", interceptor.invoke(invocation));
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE);
            verify(permissionService, never()).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.READ);
        }

        @Test
        @DisplayName("getAndDelete 应按 WRITE 鉴权（精确匹配）")
        void shouldTreatGetAndDeleteAsWrite() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE)).thenReturn(true);
            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            MethodInvocation invocation = mockInvocation(method("getAndDelete", String.class), "old", "user:1");
            assertEquals("old", interceptor.invoke(invocation));
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE);
        }

        @Test
        @DisplayName("get 应按 READ 鉴权（精确匹配，不被误判为其他类型）")
        void shouldTreatGetAsRead() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.READ)).thenReturn(true);
            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            MethodInvocation invocation = mockInvocation(method("get", String.class), "v", "user:1");
            assertEquals("v", interceptor.invoke(invocation));
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.READ);
        }

        @Test
        @DisplayName("getAll / exists / hasKey / size 等应按 READ 鉴权（精确匹配）")
        void shouldTreatExactReadMethodsAsRead() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis", AccessType.READ)).thenReturn(true);
            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            // size 无参数，无法推断 key pattern，回退到通用 cache:redis
            MethodInvocation sizeInvocation = mockInvocation(method("size"), 1L);
            assertEquals(1L, interceptor.invoke(sizeInvocation));
            verify(permissionService).isAllowed("ling-a", "cache:redis", AccessType.READ);

            // exists 带参数，可推断 key pattern
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.READ)).thenReturn(true);
            MethodInvocation existsInvocation = mockInvocation(method("exists", String.class), true, "user:1");
            assertEquals(true, interceptor.invoke(existsInvocation));
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.READ);
        }

        @Test
        @DisplayName("getAndUnknown 应按 EXECUTE 鉴权（getAnd* 前缀不落入 READ）")
        void shouldTreatGetAndUnknownAsExecute() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.EXECUTE)).thenReturn(true);
            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            MethodInvocation invocation = mockInvocation(method("getAndUnknown", String.class), "v", "user:1");
            assertEquals("v", interceptor.invoke(invocation));
            // getAnd* 前缀不应按 READ 鉴权
            verify(permissionService, never()).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.READ);
            verify(permissionService, never()).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE);
        }
    }

    @Nested
    @DisplayName("特殊路径")
    class SpecialCaseTests {

        @Test
        @DisplayName("Object 基础方法应直接放行且不做权限检查")
        void shouldBypassObjectMethods() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            MethodInvocation invocation = mockInvocation(Object.class.getMethod("toString"), "redis-template");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            assertEquals("redis-template", interceptor.invoke(invocation));
            verifyNoInteractions(permissionService);
        }

        @Test
        @DisplayName("无上下文且灵核治理开启时应拒绝执行")
        void shouldRejectWhenNoContextAndLingCoreGovernanceEnabled() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(true);
            MethodInvocation invocation = mockInvocation(method("getValue", String.class), "ok", "user:1");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class, () -> interceptor.invoke(invocation));
            assertEquals(
                    "Access Denied: LINGCORE governance is enabled but no context provided for Redis operation: getValue",
                    ex.getMessage());
            verify(permissionService).isLingCoreGovernanceEnabled();
            verify(invocation, never()).proceed();
        }

        @Test
        @DisplayName("无上下文且灵核治理关闭时应直接透传")
        void shouldAllowWhenNoContextAndLingCoreGovernanceDisabled() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(false);
            MethodInvocation invocation = mockInvocation(method("getValue", String.class), "ok", "user:1");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            assertSame("ok", interceptor.invoke(invocation));
            verify(permissionService).isLingCoreGovernanceEnabled();
        }
    }

    private Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return FakeRedisOperations.class.getMethod(name, parameterTypes);
    }

    private MethodInvocation mockInvocation(Method method, Object result, Object... args) throws Throwable {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getArguments()).thenReturn(args);
        when(invocation.proceed()).thenReturn(result);
        return invocation;
    }

    @SuppressWarnings("unused")
    private static final class FakeRedisOperations {
        public String getValue(String key) {
            return key;
        }

        public Long deleteValue(String key) {
            return 1L;
        }

        public Long deleteValues(Iterable<String> keys) {
            return 2L;
        }

        // 精确匹配 WRITE 的原子读改写方法
        public String getAndSet(String key, String value) {
            return value;
        }

        public String getAndDelete(String key) {
            return key;
        }

        public String getAndIncrement(String key) {
            return key;
        }

        public String getAndDecrement(String key) {
            return key;
        }

        public String getAndAppend(String key, String value) {
            return value;
        }

        public Long increment(String key) {
            return 1L;
        }

        public Long decrement(String key) {
            return 1L;
        }

        public String append(String key, String value) {
            return value;
        }

        public Long delete(String key) {
            return 1L;
        }

        public Boolean setIfPresent(String key, String value) {
            return true;
        }

        public Boolean setIfAbsent(String key, String value) {
            return true;
        }

        // 精确匹配 READ 的纯读方法
        public String get(String key) {
            return key;
        }

        public Map<String, String> getAll(Iterable<String> keys) {
            return Collections.emptyMap();
        }

        public String getAsString(String key) {
            return key;
        }

        public Boolean exists(String key) {
            return true;
        }

        public Boolean hasKey(String key) {
            return true;
        }

        public Long size() {
            return 1L;
        }

        // getAnd* 开头但不在精确集合中，应按 EXECUTE 处理
        public String getAndUnknown(String key) {
            return key;
        }
    }
}
