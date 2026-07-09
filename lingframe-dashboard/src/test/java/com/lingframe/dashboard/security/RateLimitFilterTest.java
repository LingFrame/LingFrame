package com.lingframe.dashboard.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流 Filter 单元测试
 * <p>
 * 覆盖：路径过滤(非 dashboard / ui 子路径放行) / IP 提取(X-Forwarded-For 多 IP 取首、X-Real-IP、RemoteAddr 回退) /
 * 令牌桶耗尽 429 / 不同 IP 独立桶 / cleanupIdleBuckets 不活跃清理。
 * <p>
 * 令牌桶容量为 30（{@link RateLimitFilter} 内部常量），耗尽后第 31 次请求返回 429。
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        chain = mock(FilterChain.class);
    }

    /** 构造请求 mock；IP 相关 header 用 lenient 避免不同分支未使用桩引发 UnnecessaryStubbingException */
    private HttpServletRequest request(String uri, String forwardedFor, String realIp, String remoteAddr) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        lenient().when(req.getRequestURI()).thenReturn(uri);
        lenient().when(req.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        lenient().when(req.getHeader("X-Real-IP")).thenReturn(realIp);
        lenient().when(req.getRemoteAddr()).thenReturn(remoteAddr);
        return req;
    }

    private HttpServletResponse responseWithBody(StringWriter sw) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        try {
            when(response.getWriter()).thenReturn(new PrintWriter(sw));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buckets() throws Exception {
        Field f = RateLimitFilter.class.getDeclaredField("perIpBuckets");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(filter);
    }

    @Nested
    @DisplayName("路径过滤")
    class PathFilterTests {

        @Test
        @DisplayName("非 dashboard 路径不应限流，直接放行且不创建令牌桶")
        void shouldNotLimitNonDashboardPath() throws Exception {
            HttpServletRequest req = request("/other/api/x", null, null, "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);

            verify(chain, times(1)).doFilter(any(), any());
            assertTrue(buckets().isEmpty(), "非 dashboard 路径不应创建令牌桶");
        }

        @Test
        @DisplayName("/lingframe/dashboard/ui 子路径不应限流（静态资源豁免）")
        void shouldNotLimitUiSubPath() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/ui/index.html", null, null, "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);

            verify(chain, times(1)).doFilter(any(), any());
            assertTrue(buckets().isEmpty(), "ui 子路径不应创建令牌桶");
        }

        @Test
        @DisplayName("/lingframe/dashboard/ 下非 ui 路径应纳入限流")
        void shouldLimitDashboardApiPath() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/lings", null, null, "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);

            verify(chain, times(1)).doFilter(any(), any());
            assertEquals(1, buckets().size(), "dashboard api 路径应创建令牌桶");
        }
    }

    @Nested
    @DisplayName("客户端 IP 提取")
    class ClientIpExtractionTests {

        @Test
        @DisplayName("X-Forwarded-For 单个 IP 应作为桶 key")
        void shouldUseXForwardedFor() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/x", "9.9.9.9", null, "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);
            assertTrue(buckets().containsKey("9.9.9.9"));
        }

        @Test
        @DisplayName("X-Forwarded-For 含多个 IP 时应取第一个（逗号分隔）")
        void shouldTakeFirstIpFromCommaSeparatedXForwardedFor() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/x", "9.9.9.9, 8.8.8.8, 7.7.7.7", null, "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);
            assertTrue(buckets().containsKey("9.9.9.9"), "应取第一个 IP 并 trim");
            assertFalse(buckets().containsKey("8.8.8.8"), "不应记录后续 IP");
        }

        @Test
        @DisplayName("X-Forwarded-For 为空时回退到 X-Real-IP")
        void shouldFallbackToXRealIp() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/x", null, "7.7.7.7", "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);
            assertTrue(buckets().containsKey("7.7.7.7"));
        }

        @Test
        @DisplayName("无代理头时回退到 RemoteAddr")
        void shouldFallbackToRemoteAddr() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/x", null, null, "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);
            assertTrue(buckets().containsKey("1.1.1.1"));
        }
    }

    @Nested
    @DisplayName("令牌桶限流")
    class TokenBucketTests {

        @Test
        @DisplayName("超过每秒容量(30)后第 31 次请求应返回 429 且不继续过滤器链")
        void shouldReturn429WhenOverLimit() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/x", null, null, "1.1.1.1");
            // 前 30 次放行
            for (int i = 0; i < 30; i++) {
                filter.doFilter(req, responseWithBody(new StringWriter()), chain);
            }
            verify(chain, times(30)).doFilter(any(), any());

            // 第 31 次应被限流
            StringWriter sw = new StringWriter();
            HttpServletResponse blocked = responseWithBody(sw);
            filter.doFilter(req, blocked, chain);

            verify(blocked).setStatus(429);
            // 链仍只被调用 30 次，第 31 次请求未继续
            verify(chain, times(30)).doFilter(any(), any());
            assertTrue(sw.toString().contains("频繁"), "429 响应体应包含限流提示");
        }

        @Test
        @DisplayName("429 响应 Content-Type 应为 application/json;charset=UTF-8")
        void shouldSetJsonContentTypeOn429() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/x", null, null, "1.1.1.1");
            for (int i = 0; i < 30; i++) {
                filter.doFilter(req, responseWithBody(new StringWriter()), chain);
            }
            HttpServletResponse blocked = responseWithBody(new StringWriter());
            filter.doFilter(req, blocked, chain);
            verify(blocked).setContentType("application/json;charset=UTF-8");
        }

        @Test
        @DisplayName("不同 IP 应有独立的令牌桶：A 耗尽后 B 首次请求仍放行")
        void shouldHaveIndependentBucketsPerIp() throws Exception {
            HttpServletRequest reqA = request("/lingframe/dashboard/api/x", null, null, "1.1.1.1");
            for (int i = 0; i < 30; i++) {
                filter.doFilter(reqA, responseWithBody(new StringWriter()), chain);
            }
            // A 第 31 次被限流
            HttpServletResponse blockedA = responseWithBody(new StringWriter());
            filter.doFilter(reqA, blockedA, chain);
            verify(blockedA).setStatus(429);

            // B 首次请求应放行
            HttpServletRequest reqB = request("/lingframe/dashboard/api/x", null, null, "2.2.2.2");
            filter.doFilter(reqB, responseWithBody(new StringWriter()), chain);

            verify(chain, times(31)).doFilter(any(), any()); // 30 (A) + 1 (B) = 31
            assertEquals(2, buckets().size(), "应有 A、B 两个独立桶");
        }
    }

    @Nested
    @DisplayName("不活跃 IP 桶清理")
    class CleanupIdleBucketsTests {

        @Test
        @DisplayName("超过 10 分钟未活跃的 IP 桶应被清理")
        void shouldCleanupIdleBucket() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/x", null, null, "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);
            Map<String, Object> map = buckets();
            assertEquals(1, map.size());

            // 通过反射将 lastAccessTime 置为 10 分钟以前，模拟长期不活跃
            Object bucket = map.get("1.1.1.1");
            Field latField = bucket.getClass().getDeclaredField("lastAccessTime");
            latField.setAccessible(true);
            latField.setLong(bucket, System.currentTimeMillis() - 600_001L);

            filter.cleanupIdleBuckets();
            assertTrue(map.isEmpty(), "不活跃桶应被清理");
        }

        @Test
        @DisplayName("活跃 IP 桶不应被清理")
        void shouldNotCleanupActiveBucket() throws Exception {
            HttpServletRequest req = request("/lingframe/dashboard/api/x", null, null, "1.1.1.1");
            filter.doFilter(req, responseWithBody(new StringWriter()), chain);
            filter.cleanupIdleBuckets();
            assertEquals(1, buckets().size(), "活跃桶应保留");
        }

        @Test
        @DisplayName("无桶时清理不应抛异常")
        void shouldNotThrowWhenNoBuckets() {
            filter.cleanupIdleBuckets(); // 空映射，仅验证不抛异常
        }
    }
}
