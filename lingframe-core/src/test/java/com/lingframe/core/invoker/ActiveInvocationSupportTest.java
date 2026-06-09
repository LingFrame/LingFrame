package com.lingframe.core.invoker;

import com.lingframe.core.ling.ActiveInvocationSnapshot;
import com.lingframe.core.ling.LingInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ActiveInvocationSupport 测试")
class ActiveInvocationSupportTest {

    @Test
    @DisplayName("无 InvocationContext 时使用 fallback 方法名")
    void shouldUseFallbackMethodNameWhenNoContext() {
        LingInstance instance = mock(LingInstance.class);
        when(instance.getVersion()).thenReturn("1.0.0");

        ActiveInvocationSnapshot snapshot = ActiveInvocationSupport.capture(instance, "fallbackMethod");

        assertNotNull(snapshot);
        assertEquals("fallbackMethod", snapshot.getMethodName());
        assertEquals("1.0.0", snapshot.getInstanceVersion());
    }

    @Test
    @DisplayName("instance 为 null 时不报错")
    void shouldHandleNullInstance() {
        ActiveInvocationSnapshot snapshot = ActiveInvocationSupport.capture(null, "someMethod");

        assertNotNull(snapshot);
        assertEquals("someMethod", snapshot.getMethodName());
    }

    @Test
    @DisplayName("fallback 为空时返回空方法名")
    void shouldHandleEmptyFallback() {
        ActiveInvocationSnapshot snapshot = ActiveInvocationSupport.capture(null, "");

        assertNotNull(snapshot);
        assertEquals("", snapshot.getMethodName());
    }
}
