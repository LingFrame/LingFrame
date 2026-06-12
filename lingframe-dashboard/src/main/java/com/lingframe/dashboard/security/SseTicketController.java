package com.lingframe.dashboard.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE Ticket 端点：为 SSE 连接颁发短期一次性 ticket
 *
 * EventSource 不支持自定义 Header，因此采用 ticket 机制：
 * 1. 前端通过带 X-Access-Token Header 的请求获取 ticket
 * 2. 用 ticket 作为 URL 参数连接 SSE（ticket 仅一次有效，60 秒过期）
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SseTicketController {

    private static final long TICKET_TTL_MS = 60_000;
    private final Map<String, Long> tickets = new ConcurrentHashMap<>();
    private final AccessTokenProperties accessTokenProperties;

    @GetMapping("/stream-ticket")
    public Map<String, String> issueTicket() {
        // 未启用 token 时，返回空 ticket（SSE 直接连接）
        if (!accessTokenProperties.isEnabled()) {
            Map<String, String> result = new HashMap<>();
            result.put("ticket", "");
            return result;
        }
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, System.currentTimeMillis());
        log.debug("签发 SSE ticket: {}", ticket);
        Map<String, String> result = new HashMap<>();
        result.put("ticket", ticket);
        return result;
    }

    /**
     * 验证并消费 ticket
     * 未启用 token 时直接放行
     */
    public boolean validateAndConsume(String ticket) {
        if (!accessTokenProperties.isEnabled()) {
            return true;
        }
        if (ticket == null || ticket.isEmpty()) {
            return false;
        }
        Long issuedAt = tickets.remove(ticket);
        if (issuedAt == null) {
            return false;
        }
        return System.currentTimeMillis() - issuedAt < TICKET_TTL_MS;
    }

    /**
     * 清理过期 ticket（定时执行，防止内存泄漏）
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 120_000)
    public void cleanupExpired() {
        if (!accessTokenProperties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<String, Long>> it = tickets.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > TICKET_TTL_MS) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("清理过期 SSE ticket: {} 个", removed);
        }
    }
}
