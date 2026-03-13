package com.lingframe.core.pipeline;

import com.lingframe.api.context.LingCallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 InvocationContext 的活跃上下文管理（current/attach/detach）
 * 和跨线程传播机制（wrap）
 */
class InvocationContextPropagationTest {

    @AfterEach
    void tearDown() {
        // 确保每个测试后清理 ThreadLocal
        InvocationContext.detach(null);
        LingCallContext.clear();
    }

    // ════════════════════════════════════════════
    // 测试 current / attach / detach 基础 API
    // ════════════════════════════════════════════

    @Test
    void current_WhenNoAttach_ShouldReturnNull() {
        assertNull(InvocationContext.current(), "初始状态下 current() 应返回 null");
    }

    @Test
    void attach_ShouldSetCurrentAndReturnPrevious() {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.setTraceId("trace-001");

        InvocationContext prev = ctx.attach();

        assertNull(prev, "首次 attach 时 prev 应为 null");
        assertSame(ctx, InvocationContext.current(), "attach 后 current() 应返回当前上下文");
        assertEquals("trace-001", InvocationContext.current().getTraceId());

        // 清理
        InvocationContext.detach(prev);
        ctx.recycle();
    }

    @Test
    void detach_ShouldRestorePreviousContext() {
        InvocationContext outer = InvocationContext.obtain();
        outer.setTraceId("outer-trace");
        InvocationContext prevOuter = outer.attach();

        InvocationContext inner = InvocationContext.obtain();
        inner.setTraceId("inner-trace");
        InvocationContext prevInner = inner.attach();

        // 当前应该是 inner
        assertEquals("inner-trace", InvocationContext.current().getTraceId());

        // detach inner，应恢复 outer
        InvocationContext.detach(prevInner);
        assertEquals("outer-trace", InvocationContext.current().getTraceId());

        // detach outer，应恢复 null
        InvocationContext.detach(prevOuter);
        assertNull(InvocationContext.current());

        inner.recycle();
        outer.recycle();
    }

    // ════════════════════════════════════════════
    // 测试 wrap(Callable) 跨线程传播
    // ════════════════════════════════════════════

    @Test
    void wrapCallable_ShouldPropagateTraceIdToChildThread() throws Exception {
        InvocationContext parent = InvocationContext.obtain();
        parent.setTraceId("propagated-trace-id");
        parent.setCallerLingId("caller-ling-001");
        parent.setTargetLingId("target-ling-001");
        InvocationContext prev = parent.attach();
        LingCallContext.setLingId("ling-a");
        Map<String, String> labels = new HashMap<>();
        labels.put("env", "canary");
        LingCallContext.setLabels(labels);
        LingCallContext.setTraceId("trace-parent");

        try {
            // 在父线程中 wrap 一个 Callable
            Callable<String> wrapped = InvocationContext.wrap(() -> {
                InvocationContext child = InvocationContext.current();
                assertNotNull(child, "子线程中 current() 不应为 null");
                return child.getTraceId() + "|" + child.getCallerLingId() + "|" + child.getTargetLingId()
                        + "|" + LingCallContext.getLingId()
                        + "|" + LingCallContext.getTraceId()
                        + "|" + LingCallContext.getLabels().get("env");
            });

            // 在另一个线程中执行
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> future = executor.submit(wrapped);
                String result = future.get(5, TimeUnit.SECONDS);
                assertEquals("propagated-trace-id|caller-ling-001|target-ling-001|ling-a|trace-parent|canary", result,
                        "子线程应继承父线程的 traceId、callerLingId、targetLingId");
            } finally {
                executor.shutdown();
            }
        } finally {
            InvocationContext.detach(prev);
            parent.recycle();
            LingCallContext.clear();
        }
    }

    @Test
    void wrapCallable_WhenNoParentContext_ShouldStillWorkWithEmptyChild() throws Exception {
        // 确保没有活跃上下文
        assertNull(InvocationContext.current());

        Callable<Boolean> wrapped = InvocationContext.wrap(() -> {
            InvocationContext child = InvocationContext.current();
            // child 不为 null（wrap 创建了 child 并 attach），但字段应为空
            assertNotNull(child);
            assertNull(child.getTraceId());
            return true;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(executor.submit(wrapped).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void wrap_ShouldNotClearLingIdWhenTraceMissing() throws Exception {
        LingCallContext.setLingId("ling-x");
        Map<String, String> labels = new HashMap<>();
        labels.put("k", "v");
        LingCallContext.setLabels(labels);
        LingCallContext.clearTraceId();

        Runnable wrapped = InvocationContext.wrap(() -> {
            assertEquals("ling-x", LingCallContext.getLingId());
            assertEquals("v", LingCallContext.getLabels().get("k"));
            assertNull(LingCallContext.getTraceId());
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(wrapped).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
    }

    // ════════════════════════════════════════════
    // 测试 wrap(Runnable) 跨线程传播
    // ════════════════════════════════════════════

    @Test
    void wrapRunnable_ShouldPropagateContextToChildThread() throws Exception {
        InvocationContext parent = InvocationContext.obtain();
        parent.setTraceId("runnable-trace");
        parent.setServiceFQSID("test-ling:com.example.Service");
        InvocationContext prev = parent.attach();

        try {
            CompletableFuture<String> result = new CompletableFuture<>();

            Runnable wrapped = InvocationContext.wrap(() -> {
                InvocationContext child = InvocationContext.current();
                assertNotNull(child);
                result.complete(child.getTraceId() + "|" + child.getServiceFQSID());
            });

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(wrapped);
                assertEquals("runnable-trace|test-ling:com.example.Service",
                        result.get(5, TimeUnit.SECONDS),
                        "Runnable wrap 应传播 traceId 和 serviceFQSID");
            } finally {
                executor.shutdown();
            }
        } finally {
            InvocationContext.detach(prev);
            parent.recycle();
        }
    }

    // ════════════════════════════════════════════
    // 测试子线程执行后清理
    // ════════════════════════════════════════════

    @Test
    void wrap_ShouldCleanupChildContextAfterExecution() throws Exception {
        InvocationContext parent = InvocationContext.obtain();
        parent.setTraceId("cleanup-test");
        InvocationContext prev = parent.attach();
        LingCallContext.setLingId("ling-cleanup");
        LingCallContext.setTraceId("trace-cleanup");

        try {
            CompletableFuture<Void> childDone = new CompletableFuture<>();
            CompletableFuture<InvocationContext> afterCleanup = new CompletableFuture<>();
            CompletableFuture<String> afterContext = new CompletableFuture<>();

            Runnable wrapped = InvocationContext.wrap(() -> {
                // 在 wrap 内部，子线程有活跃上下文
                assertNotNull(InvocationContext.current());
                childDone.complete(null);
            });

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                // 执行 wrapped，完成后检查子线程的 ThreadLocal 是否已清理
                executor.submit(() -> {
                    wrapped.run();
                    // wrap 的 finally 已经 detach + recycle，此时子线程不应有活跃上下文
                    afterCleanup.complete(InvocationContext.current());
                    afterContext.complete(String.valueOf(LingCallContext.getLingId())
                            + "|" + String.valueOf(LingCallContext.getTraceId()));
                });

                childDone.get(5, TimeUnit.SECONDS);
                assertEquals("null|null", afterContext.get(5, TimeUnit.SECONDS));
                assertNull(afterCleanup.get(5, TimeUnit.SECONDS),
                        "wrap 执行完毕后子线程的 current() 应为 null（已清理）");
            } finally {
                executor.shutdown();
            }
        } finally {
            InvocationContext.detach(prev);
            parent.recycle();
            LingCallContext.clear();
        }
    }
}
