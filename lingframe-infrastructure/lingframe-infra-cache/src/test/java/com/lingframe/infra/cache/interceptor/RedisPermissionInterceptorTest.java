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
        @DisplayName("key pattern 未命中时应回退到通用 Redis WRITE 权限")
        void shouldFallbackToGenericCapabilityWhenPatternPermissionIsMissing() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE)).thenReturn(false);
            when(permissionService.isAllowed("ling-a", "cache:redis", AccessType.WRITE)).thenReturn(false);
            MethodInvocation invocation = mockInvocation(method("deleteValue", String.class), 1L, "user:1");

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class, () -> interceptor.invoke(invocation));
            assertEquals("Ling [ling-a] denied access to Redis operation: deleteValue", ex.getMessage());
            verify(permissionService).isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE);
            verify(permissionService).isAllowed("ling-a", "cache:redis", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:redis", "deleteValue", false);
            verify(invocation, never()).proceed();
        }

        @Test
        @DisplayName("多 key 操作应要求所有 key pattern capability 均被允许")
        void shouldRequireAllKeyPatternsForMultiKeyOperation() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:user:*", AccessType.WRITE)).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "cache:redis:key:order:*", AccessType.WRITE)).thenReturn(false);
            when(permissionService.isAllowed("ling-a", "cache:redis", AccessType.WRITE)).thenReturn(false);
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
            verify(permissionService).isAllowed("ling-a", "cache:redis", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:redis", "deleteValues", false);
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
    }
}
