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
        @DisplayName("get 前缀方法应按 READ 鉴权")
        void shouldTreatGetPrefixAsRead() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis", AccessType.READ)).thenReturn(true);
            MethodInvocation invocation = mockInvocation(method("getValue", String.class), "ok", "user:1");

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            assertEquals("ok", interceptor.invoke(invocation));
            verify(permissionService).isAllowed("ling-a", "cache:redis", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:redis", "getValue", true);
        }

        @Test
        @DisplayName("delete 前缀方法应按 WRITE 鉴权")
        void shouldTreatDeletePrefixAsWrite() throws Throwable {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:redis", AccessType.WRITE)).thenReturn(false);
            MethodInvocation invocation = mockInvocation(method("deleteValue", String.class), 1L, "user:1");

            LingCallContext.setLingId("ling-a");
            RedisPermissionInterceptor interceptor = new RedisPermissionInterceptor(permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class, () -> interceptor.invoke(invocation));
            assertEquals("Ling [ling-a] denied access to Redis operation: deleteValue", ex.getMessage());
            verify(permissionService).isAllowed("ling-a", "cache:redis", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:redis", "deleteValue", false);
            verify(invocation, never()).proceed();
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
    }
}
