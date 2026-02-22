package com.lingframe.core.kernel;

import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.ling.LingRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GovernanceKernelTest {

    private GovernanceKernel kernel;
    private PermissionService mockPermService;
    private GovernanceArbitrator mockArbitrator;
    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        // PermissionService 不是函数式接口
        mockPermService = new PermissionService() {
            @Override
            public boolean isAllowed(String lingId, String capability, AccessType accessType) {
                return true;
            }

            @Override
            public void grant(String lingId, String capability, AccessType accessType) {
            }

            @Override
            public PermissionInfo getPermission(String lingId, String capability) {
                return null;
            }

            @Override
            public void audit(String lingId, String capability, String operation, boolean allowed) {
            }
        };

        eventBus = new EventBus();
    }

    private void initKernel(GovernanceDecision decision) {
        // GovernanceArbitrator 是普通类
        mockArbitrator = new GovernanceArbitrator(Collections.emptyList()) {
            @Override
            public GovernanceDecision arbitrate(LingRuntime runtime, java.lang.reflect.Method method,
                    InvocationContext ctx) {
                return decision;
            }
        };
        kernel = new GovernanceKernel(mockPermService, mockArbitrator, eventBus);
    }

    @Test
    @DisplayName("测试重试机制：在达到最大重试次数前成功")
    void testRetryOnFailure_SuccessAfterRetries() {
        GovernanceDecision decision = GovernanceDecision.builder()
                .retryCount(3)
                .build();
        initKernel(decision);

        InvocationContext ctx = InvocationContext.builder()
                .resourceId("testResource")
                .callerLingId("caller1")
                .lingId("test-ling")
                .build();

        AtomicInteger callCount = new AtomicInteger(0);

        Object result = kernel.invoke(null, null, ctx, () -> {
            int current = callCount.incrementAndGet();
            if (current < 3) {
                throw new RuntimeException("Transient failure");
            }
            return "success-result";
        });

        assertEquals("success-result", result);
        assertEquals(3, callCount.get(), "总共应该被调用3次（最初1次 + 重试2次）");
    }

    @Test
    @DisplayName("测试重试机制：超过最大重试次数后抛出异常")
    void testRetryOnFailure_FailAfterMaxRetries() {
        GovernanceDecision decision = GovernanceDecision.builder()
                .retryCount(2)
                .build();
        initKernel(decision);

        InvocationContext ctx = InvocationContext.builder()
                .resourceId("testResource")
                .callerLingId("caller1")
                .lingId("test-ling")
                .build();

        AtomicInteger callCount = new AtomicInteger(0);

        RuntimeException e = assertThrows(RuntimeException.class, () -> {
            kernel.invoke(null, null, ctx, () -> {
                callCount.incrementAndGet();
                throw new RuntimeException("Persistent failure");
            });
        });

        assertEquals("Persistent failure", e.getMessage());
        assertEquals(3, callCount.get(), "总共应该被调用3次（最初1次 + 耗尽2次重试机会）");
    }

    @Test
    @DisplayName("测试降级机制：重试耗尽后触发 Fallback 并返回设定值")
    void testFallbackTriggered_AfterMaxRetries() {
        GovernanceDecision decision = GovernanceDecision.builder()
                .retryCount(1)
                .fallbackValue("fallback-result")
                .build();
        initKernel(decision);

        InvocationContext ctx = InvocationContext.builder()
                .resourceId("testResource")
                .callerLingId("caller1")
                .lingId("test-ling")
                .build();

        AtomicInteger callCount = new AtomicInteger(0);

        Object result = kernel.invoke(null, null, ctx, () -> {
            callCount.incrementAndGet();
            throw new RuntimeException("Service failure");
        });

        assertEquals("fallback-result", result, "重试耗尽后应当返回设置的 Fallback 兜底值");
        assertEquals(2, callCount.get(), "总共应该被调用2次（最初1次 + 1次重试）");
    }

    @Test
    @DisplayName("测试权限异常：PermissionDeniedException 不予重试")
    void testNoRetryForPermissionDenied() {
        GovernanceDecision decision = GovernanceDecision.builder()
                .retryCount(5)
                .build();
        initKernel(decision);

        InvocationContext ctx = InvocationContext.builder()
                .resourceId("testResource")
                .callerLingId("caller1")
                .lingId("test-ling")
                .build();

        AtomicInteger callCount = new AtomicInteger(0);

        PermissionDeniedException e = assertThrows(PermissionDeniedException.class, () -> {
            kernel.invoke(null, null, ctx, () -> {
                callCount.incrementAndGet();
                throw new PermissionDeniedException("test-ling", "test-resource");
            });
        });

        // 哪怕设定了 5 次重试，对于拒绝访问的异常应当直接穿透
        assertEquals(1, callCount.get(), "权限不足异常不应触发重试逻辑");
    }
}
