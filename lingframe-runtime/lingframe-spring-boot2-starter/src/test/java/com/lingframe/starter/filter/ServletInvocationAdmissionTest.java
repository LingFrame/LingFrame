package com.lingframe.starter.filter;

import com.lingframe.core.invoker.InvocationAdmission;
import com.lingframe.core.ling.ActiveInvocationSnapshot;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationContext;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Servlet 同步区间与异步完成事实的准入释放测试。 */
@DisplayName("Servlet 请求准入释放边界")
class ServletInvocationAdmissionTest {
    private LingInstance instance;
    private InvocationAdmission lease;
    private ServletInvocationAdmission adapter;

    @BeforeEach
    void setUp() {
        instance = mock(LingInstance.class);
        when(instance.beginInvocation(any(ActiveInvocationSnapshot.class))).thenReturn(1L);
        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.routing().setTargetInstance(instance);
            lease = InvocationAdmission.acquire(ctx);
            adapter = new ServletInvocationAdmission(lease);
        } finally {
            ctx.recycle();
        }
    }

    @AfterEach
    void cleanUp() {
        lease.close();
    }

    @Test
    @DisplayName("同步请求保持原对象且重复关闭只完成一次")
    void synchronousRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        assertSame(request, adapter.wrap(request, mock(HttpServletResponse.class)));
        adapter.close();
        adapter.close();
        verify(instance, times(1)).completeInvocation(1L);
    }

    @Test
    @DisplayName("进入适配器前已开启异步也必须等待完成")
    void alreadyAsynchronousRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        AsyncContext async = mock(AsyncContext.class);
        when(request.isAsyncSupported()).thenReturn(true);
        when(request.isAsyncStarted()).thenReturn(true);
        when(request.getAsyncContext()).thenReturn(async);
        adapter.wrap(request, mock(HttpServletResponse.class));
        adapter.close();
        verify(async).addListener(adapter);
        verify(instance, never()).completeInvocation(anyLong());
        adapter.onComplete(new AsyncEvent(async));
        verify(instance).completeInvocation(1L);
    }

    @Test
    @DisplayName("超时和错误不释放凭据，异步完成后才释放")
    void asyncCompletionOnly() throws Exception {
        AsyncContext async = mock(AsyncContext.class);
        HttpServletRequest wrapped = wrappedRequest(async);
        assertSame(async, wrapped.startAsync());
        verify(async).addListener(adapter);
        adapter.close();
        adapter.onTimeout(new AsyncEvent(async));
        adapter.onError(new AsyncEvent(async));
        verify(instance, never()).completeInvocation(anyLong());
        adapter.onComplete(new AsyncEvent(async));
        adapter.onComplete(new AsyncEvent(async));
        verify(instance, times(1)).completeInvocation(1L);
    }

    @Test
    @DisplayName("异步完成早于初始业务返回时仍保留在途计数")
    void completionBeforeReturn() throws Exception {
        AsyncContext async = mock(AsyncContext.class);
        wrappedRequest(async).startAsync();
        adapter.onComplete(new AsyncEvent(async));
        verify(instance, never()).completeInvocation(anyLong());
        adapter.close();
        verify(instance).completeInvocation(1L);
    }

    @Test
    @DisplayName("显式异步参数和再分派周期均继续注册完成监听")
    void explicitStartAndRedispatch() throws Exception {
        AsyncContext first = mock(AsyncContext.class);
        HttpServletRequest request = wrappedRequest(first);
        HttpServletResponse response = mock(HttpServletResponse.class);
        request.startAsync(request, response);
        adapter.close();
        AsyncContext next = mock(AsyncContext.class);
        adapter.onStartAsync(new AsyncEvent(next));
        verify(next).addListener(adapter);
        verify(instance, never()).completeInvocation(anyLong());
        adapter.onComplete(new AsyncEvent(next));
        verify(instance).completeInvocation(1L);
    }

    @Test
    @DisplayName("监听登记失败不得假报已完成")
    void listenerFailureRetainsAdmission() {
        AsyncContext async = mock(AsyncContext.class);
        doThrow(new IllegalStateException("cannot register")).when(async).addListener(adapter);
        HttpServletRequest request = wrappedRequest(async);
        assertThrows(IllegalStateException.class, request::startAsync);
        adapter.close();
        verify(instance, never()).completeInvocation(anyLong());
    }

    private HttpServletRequest wrappedRequest(AsyncContext async) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.isAsyncSupported()).thenReturn(true);
        when(request.startAsync(any(ServletRequest.class), any(ServletResponse.class))).thenReturn(async);
        return adapter.wrap(request, mock(HttpServletResponse.class));
    }
}
