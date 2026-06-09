package com.lingframe.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Exception 类测试")
class ExceptionTest {

    @Test
    @DisplayName("ClassLoaderException 基本构造")
    void shouldCreateClassLoaderException() {
        ClassLoaderException ex = new ClassLoaderException("load failed");
        assertEquals("load failed", ex.getMessage());
        assertNull(ex.getLingId());
        assertNull(ex.getResource());
    }

    @Test
    @DisplayName("ClassLoaderException 带原因构造")
    void shouldCreateClassLoaderExceptionWithCause() {
        Throwable cause = new RuntimeException("root cause");
        ClassLoaderException ex = new ClassLoaderException("load failed", cause);
        assertEquals("load failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("ClassLoaderException 带 lingId 和 resource")
    void shouldCreateClassLoaderExceptionWithLingIdAndResource() {
        ClassLoaderException ex = new ClassLoaderException("order-ling", "order-api.jar", "jar corrupt");
        assertEquals("jar corrupt", ex.getMessage());
        assertEquals("order-ling", ex.getLingId());
        assertEquals("order-api.jar", ex.getResource());
    }

    @Test
    @DisplayName("ClassLoaderException 完整构造")
    void shouldCreateClassLoaderExceptionFull() {
        Throwable cause = new java.io.IOException("disk error");
        ClassLoaderException ex = new ClassLoaderException("order-ling", "order-api.jar", "jar corrupt", cause);
        assertEquals("jar corrupt", ex.getMessage());
        assertEquals("order-ling", ex.getLingId());
        assertEquals("order-api.jar", ex.getResource());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("LingInstallException 基本构造")
    void shouldCreateLingInstallException() {
        LingInstallException ex = new LingInstallException("order-ling", "install failed");
        assertEquals("install failed", ex.getMessage());
        assertEquals("order-ling", ex.getLingId());
    }

    @Test
    @DisplayName("LingInstallException 带原因构造")
    void shouldCreateLingInstallExceptionWithCause() {
        Throwable cause = new RuntimeException("dependency missing");
        LingInstallException ex = new LingInstallException("order-ling", "install failed", cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("LingSecurityException 基本构造")
    void shouldCreateLingSecurityException() {
        LingSecurityException ex = new LingSecurityException("evil-ling", "dangerous API detected");
        assertEquals("dangerous API detected", ex.getMessage());
        assertEquals("evil-ling", ex.getLingId());
    }

    @Test
    @DisplayName("LingSecurityException 带原因构造")
    void shouldCreateLingSecurityExceptionWithCause() {
        Throwable cause = new RuntimeException("scan error");
        LingSecurityException ex = new LingSecurityException("evil-ling", "dangerous API", cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("IllegalStateTransitionException 构造")
    void shouldCreateIllegalStateTransitionException() {
        IllegalStateTransitionException ex = new IllegalStateTransitionException(
                TestState.STARTED, TestState.DESTROYED);
        assertTrue(ex.getMessage().contains("STARTED"));
        assertTrue(ex.getMessage().contains("DESTROYED"));
    }

    private enum TestState {
        STARTED, DESTROYED
    }
}
