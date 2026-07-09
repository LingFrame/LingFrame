package com.lingframe.starter.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link LingRepeatableReadFilter} 补充测试。
 * <p>
 * 该类通过动态代理在运行时适配 javax.servlet 或 jakarta.servlet，
 * 实现请求体可重复读。本测试在 javax.servlet 环境下验证：
 * <ul>
 *   <li>createProxy 生成 Filter 代理</li>
 *   <li>init/destroy 返回 null</li>
 *   <li>Object 方法（toString/hashCode/equals）的特殊处理</li>
 *   <li>doFilter 包装请求使其可重复读，并标记 ATTRIBUTE 避免重复包装</li>
 * </ul>
 * <p>
 * 注意：FilterChain 必须使用 Mockito mock（生成 public 子类），
 * 不能用匿名类或 lambda。原因：{@code LingRepeatableReadFilter} 内部通过
 * {@link org.springframework.util.ReflectionUtils#invokeMethod} 反射调用 chain.doFilter，
 * 而 ReflectionUtils.invokeMethod 不会调用 makeAccessible，因此当 doFilter 的声明类
 * 不可见（如包级私有的匿名/lambda 类）时会抛 IllegalAccessException。
 */
@DisplayName("LingRepeatableReadFilter 补充测试")
class LingRepeatableReadFilterSupplementTest {

    @Test
    @DisplayName("createProxy 应返回实现 javax.servlet.Filter 的代理对象")
    void shouldCreateProxyImplementingFilter() {
        Object proxy = LingRepeatableReadFilter.createProxy();

        assertNotNull(proxy);
        // 当前环境为 javax.servlet，代理应实现 javax.servlet.Filter
        assertTrue(proxy instanceof Filter,
                "代理应实现 javax.servlet.Filter，实际: " + (proxy == null ? "null" : proxy.getClass().getName()));
    }

    @Test
    @DisplayName("代理的 toString/hashCode/equals 应被特殊处理")
    void shouldHandleObjectMethods() {
        Object proxy = LingRepeatableReadFilter.createProxy();
        assertNotNull(proxy);

        // toString 返回固定标识
        assertEquals("LingRepeatableReadFilterProxy", proxy.toString());
        // hashCode 返回身份哈希
        assertEquals(System.identityHashCode(proxy), proxy.hashCode());
        // equals 走身份比较
        assertEquals(proxy, proxy);
        // 与不同对象比较应返回 false
        assertFalse(proxy.equals("other"));
    }

    @Test
    @DisplayName("init 与 destroy 应返回 null 且不抛异常")
    void shouldReturnNullForInitAndDestroy() throws Exception {
        Filter proxy = (Filter) LingRepeatableReadFilter.createProxy();
        // init/destroy 在 Filter 接口中是 default 方法，这里直接调用验证不抛异常
        proxy.init(null);
        proxy.destroy();
    }

    @Test
    @DisplayName("doFilter 应包装请求体使其可重复读，并只包装一次")
    void shouldWrapRequestForRepeatableRead() throws Exception {
        Filter proxy = (Filter) LingRepeatableReadFilter.createProxy();
        byte[] body = "hello-ling-frame".getBytes("UTF-8");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        request.setCharacterEncoding("UTF-8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        doNothing().when(chain).doFilter(any(ServletRequest.class), any());

        proxy.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain, times(1)).doFilter(captor.capture(), any());

        ServletRequest captured = captor.getValue();
        assertNotNull(captured);
        // 传入 chain 的应是包装后的请求（不是原始 request）
        assertNotSame(request, captured, "应传入包装后的请求");
        // 标记属性应已设置在原始 request 上，避免后续重复包装
        Object marked = request.getAttribute(
                LingRepeatableReadFilter.class.getName() + ".FILTERED");
        assertTrue(Boolean.TRUE.equals(marked), "应设置 FILTERED 标记属性");
    }

    @Test
    @DisplayName("doFilter 在已标记 FILTERED 时应直接转发原始请求不再包装")
    void shouldSkipWrappingWhenAlreadyFiltered() throws Exception {
        Filter proxy = (Filter) LingRepeatableReadFilter.createProxy();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("body".getBytes("UTF-8"));
        // 预先标记，模拟嵌套 Filter 场景
        request.setAttribute(LingRepeatableReadFilter.class.getName() + ".FILTERED", Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        doNothing().when(chain).doFilter(any(ServletRequest.class), any());

        proxy.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain, times(1)).doFilter(captor.capture(), any());
        // 已标记时应直接转发原始请求（不包装）
        assertEquals(request, captor.getValue(), "已过滤时应直接转发原始请求");
    }

    @Test
    @DisplayName("包装后的请求应支持多次读取输入流")
    void shouldAllowRepeatedInputStreamReads() throws Exception {
        Filter proxy = (Filter) LingRepeatableReadFilter.createProxy();
        byte[] body = "repeatable".getBytes("UTF-8");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        request.setCharacterEncoding("UTF-8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        doNothing().when(chain).doFilter(any(ServletRequest.class), any());

        proxy.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any());
        ServletRequest wrapped = captor.getValue();
        assertNotNull(wrapped);
        // 第一次读取
        byte[] firstRead = readAll(wrapped.getInputStream());
        assertArrayEquals(body, firstRead);
        // 第二次读取（验证可重复读）
        byte[] secondRead = readAll(wrapped.getInputStream());
        assertArrayEquals(body, secondRead);
    }

    @Test
    @DisplayName("包装后的请求 getReader 应返回基于缓存的 Reader")
    void shouldReturnReaderFromCache() throws Exception {
        Filter proxy = (Filter) LingRepeatableReadFilter.createProxy();
        byte[] body = "reader-content".getBytes("UTF-8");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        request.setCharacterEncoding("UTF-8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        doNothing().when(chain).doFilter(any(ServletRequest.class), any());

        proxy.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any());
        ServletRequest wrapped = captor.getValue();
        assertNotNull(wrapped);
        String text = wrapped.getReader().lines()
                .reduce("", (a, b) -> a + b);
        assertEquals("reader-content", text);
    }

    @Test
    @DisplayName("代理对象应通过 Object.getClass 返回 JDK 动态代理类")
    void shouldReturnJdkProxyClass() throws Exception {
        Object proxy = LingRepeatableReadFilter.createProxy();
        assertNotNull(proxy);
        // getClass 是 Object 的 final 方法，不会被转发到 InvocationHandler，直接返回代理类
        Class<?> proxyClass = proxy.getClass();
        // 代理类名应符合 JDK 动态代理命名规范
        assertTrue(proxyClass.getName().contains("$Proxy"));
    }

    private static byte[] readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }
}
