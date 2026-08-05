package com.lingframe.starter.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot 3.x 可重复读过滤器单测。
 * <p>
 * 验证 {@link JakartaRepeatableReadFilter}：
 * <ul>
 *   <li>请求体被缓存，可多次读取</li>
 *   <li>已标记 FILTERED 时不再包装</li>
 *   <li>getReader 与 getInputStream 一致</li>
 *   <li>{@link JakartaRepeatableReadFilterFactory} 通过 SPI 暴露</li>
 * </ul>
 */
@DisplayName("JakartaRepeatableReadFilter 单测")
class JakartaRepeatableReadFilterTest {

    @Test
    @DisplayName("doFilter 应包装请求体使其可重复读，并只包装一次")
    void shouldWrapRequestForRepeatableRead() throws Exception {
        JakartaRepeatableReadFilter filter = new JakartaRepeatableReadFilter();
        byte[] body = "hello-ling-frame".getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        request.setCharacterEncoding("UTF-8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captor = new AtomicReference<>();
        FilterChain chain = (req, res) -> captor.set((HttpServletRequest) req);

        filter.doFilter(request, response, chain);

        HttpServletRequest captured = captor.get();
        assertNotNull(captured, "应捕获到传入 chain.doFilter 的请求");
        assertNotSame(request, captured, "应传入包装后的请求");
        assertTrue(Boolean.TRUE.equals(request.getAttribute(
                JakartaRepeatableReadFilter.class.getName() + ".FILTERED")), "应设置 FILTERED 标记属性");
    }

    @Test
    @DisplayName("doFilter 在已标记 FILTERED 时应直接转发原始请求不再包装")
    void shouldSkipWrappingWhenAlreadyFiltered() throws Exception {
        JakartaRepeatableReadFilter filter = new JakartaRepeatableReadFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("body".getBytes(StandardCharsets.UTF_8));
        request.setAttribute(JakartaRepeatableReadFilter.class.getName() + ".FILTERED", Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captor = new AtomicReference<>();
        FilterChain chain = (req, res) -> captor.set((HttpServletRequest) req);

        filter.doFilter(request, response, chain);

        assertEquals(request, captor.get(), "已过滤时应直接转发原始请求");
    }

    @Test
    @DisplayName("包装后的请求应支持多次读取输入流")
    void shouldAllowRepeatedInputStreamReads() throws Exception {
        JakartaRepeatableReadFilter filter = new JakartaRepeatableReadFilter();
        byte[] body = "repeatable".getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        request.setCharacterEncoding("UTF-8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captor = new AtomicReference<>();
        FilterChain chain = (req, res) -> captor.set((HttpServletRequest) req);

        filter.doFilter(request, response, chain);
        HttpServletRequest wrapped = captor.get();
        assertNotNull(wrapped);

        byte[] firstRead = readAll(wrapped.getInputStream());
        assertArrayEquals(body, firstRead);
        byte[] secondRead = readAll(wrapped.getInputStream());
        assertArrayEquals(body, secondRead);
    }

    @Test
    @DisplayName("包装后的请求 getReader 应返回基于缓存的 Reader")
    void shouldReturnReaderFromCache() throws Exception {
        JakartaRepeatableReadFilter filter = new JakartaRepeatableReadFilter();
        byte[] body = "reader-content".getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        request.setCharacterEncoding("UTF-8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captor = new AtomicReference<>();
        FilterChain chain = (req, res) -> captor.set((HttpServletRequest) req);

        filter.doFilter(request, response, chain);
        HttpServletRequest wrapped = captor.get();
        assertNotNull(wrapped);

        BufferedReader reader = wrapped.getReader();
        String text = reader.lines().reduce("", (a, b) -> a + b);
        assertEquals("reader-content", text);
    }

    @Test
    @DisplayName("doFilter 对 multipart/form-data 请求应放行原始请求不包装，避免消费 InputStream 导致容器解析 Part 失败")
    void shouldBypassWrappingForMultipartRequest() throws Exception {
        JakartaRepeatableReadFilter filter = new JakartaRepeatableReadFilter();
        byte[] body = "--boundary\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\ndummy\r\n--boundary--\r\n"
                .getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body);
        request.setCharacterEncoding("UTF-8");
        request.setContentType("multipart/form-data; boundary=boundary");
        request.setMethod("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captor = new AtomicReference<>();
        FilterChain chain = (req, res) -> captor.set((HttpServletRequest) req);

        filter.doFilter(request, response, chain);

        assertEquals(request, captor.get(), "multipart 请求应直接转发原始请求不包装");
    }

    @Test
    @DisplayName("工厂应返回 jakarta.servlet 实现，且 servletApiPackage 正确")
    void shouldExposeJakartaFactory() {
        JakartaRepeatableReadFilterFactory factory = new JakartaRepeatableReadFilterFactory();
        assertNotNull(factory.createFilterRegistration());
        assertEquals("jakarta.servlet", factory.servletApiPackage());
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }
}
