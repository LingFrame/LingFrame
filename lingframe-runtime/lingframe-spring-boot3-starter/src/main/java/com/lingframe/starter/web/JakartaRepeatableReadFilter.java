package com.lingframe.starter.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Spring Boot 3.x 专用可重复读过滤器。
 * <p>
 * 基于 {@code jakarta.servlet} 实现：缓存请求体，使下游可多次读取。
 * 该类与 {@code lingframe-spring-boot2-starter} 中的 {@code JavaxRepeatableReadFilter}
 * 形成对等实现，差异仅在于 Servlet API 命名空间。
 */
public class JakartaRepeatableReadFilter implements Filter {

    private static final String FILTERED_ATTRIBUTE =
            JakartaRepeatableReadFilter.class.getName() + ".FILTERED";

    @Override
    public void init(FilterConfig filterConfig) {
        // 无初始化逻辑
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if (Boolean.TRUE.equals(httpRequest.getAttribute(FILTERED_ATTRIBUTE))) {
            chain.doFilter(request, response);
            return;
        }
        httpRequest.setAttribute(FILTERED_ATTRIBUTE, Boolean.TRUE);
        chain.doFilter(new RepeatableReadRequest(httpRequest), response);
    }

    @Override
    public void destroy() {
        // 无销毁逻辑
    }

    /**
     * 包装请求：缓存 body 并支持多次读取。
     */
    private static final class RepeatableReadRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        RepeatableReadRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = readAll(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(cachedBody);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            if (encoding == null) {
                encoding = "ISO-8859-1";
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
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

    /**
     * 基于缓存的 ServletInputStream，支持多次读取。
     */
    private static final class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream bais;

        CachedServletInputStream(byte[] body) {
            this.bais = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return bais.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // 同步读取，无需监听
        }

        @Override
        public int read() throws IOException {
            return bais.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return bais.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return bais.available();
        }

        @Override
        public void close() throws IOException {
            bais.close();
        }
    }
}
