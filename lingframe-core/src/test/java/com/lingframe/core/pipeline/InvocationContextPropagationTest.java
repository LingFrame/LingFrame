package com.lingframe.core.pipeline;

import com.lingframe.api.context.LingCallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InvocationContext 传播测试")
class InvocationContextPropagationTest {

    @AfterEach
    void tearDown() {
        InvocationContext.detach(null);
        LingCallContext.clear();
    }

    @Nested
    @DisplayName("线程内绑定")
    class ThreadBindingTests {

        @Test
        @DisplayName("当前线程无上下文时应返回 null")
        void shouldReturnNullWhenNoCurrentContextExists() {
            assertNull(InvocationContext.current());
        }

        @Test
        @DisplayName("嵌套绑定时应正确恢复外层上下文")
        void shouldAttachAndRestoreNestedContexts() {
            InvocationContext outer = InvocationContext.obtain();
            outer.setTraceId("outer");
            InvocationContext outerPrevious = outer.attach();

            InvocationContext inner = InvocationContext.obtain();
            inner.setTraceId("inner");
            InvocationContext innerPrevious = inner.attach();

            assertEquals("inner", InvocationContext.current().getTraceId());

            InvocationContext.detach(innerPrevious);
            assertEquals("outer", InvocationContext.current().getTraceId());

            InvocationContext.detach(outerPrevious);
            assertNull(InvocationContext.current());

            inner.recycle();
            outer.recycle();
        }
    }

    @Nested
    @DisplayName("跨线程传播")
    class CrossThreadPropagationTests {

        @Test
        @DisplayName("包装后的 Callable 应携带分区化状态到子线程")
        void shouldPropagatePartitionedStateIntoWrappedCallable() throws Exception {
            InvocationContext parent = InvocationContext.obtain();
            parent.setTraceId("trace-parent");
            parent.setCallerLingId("caller-a");
            parent.setTargetLingId("target-b");
            parent.resolution().setTargetClassName("demo.Service");
            parent.governance().setTimeoutMs(99);
            parent.governance().setRateLimitPerSecond(7);
            parent.governance().setMaxConcurrentThreads(3);
            parent.execution().setMode(InvocationExecutionMode.SIMULATION);
            InvocationContext previous = parent.attach();

            LingCallContext.setLingId("ling-a");
            LingCallContext.setTraceId("trace-ctx");
            Map<String, String> labels = new HashMap<>();
            labels.put("env", "canary");
            LingCallContext.setLabels(labels);

            try {
                Callable<String> wrapped = InvocationContext.wrap(() -> {
                    InvocationContext child = InvocationContext.current();
                    assertNotNull(child);
                    return child.getTraceId()
                            + "|" + child.getCallerLingId()
                            + "|" + child.getTargetLingId()
                            + "|" + child.resolution().getTargetClassName()
                            + "|" + child.governance().getTimeoutMs()
                            + "|" + child.governance().getRateLimitPerSecond()
                            + "|" + child.governance().getMaxConcurrentThreads()
                            + "|" + child.execution().getMode()
                            + "|" + LingCallContext.getLingId()
                            + "|" + LingCallContext.getTraceId()
                            + "|" + LingCallContext.getLabels().get("env");
                });

                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    String result = executor.submit(wrapped).get(5, TimeUnit.SECONDS);
                    assertEquals("trace-parent|caller-a|target-b|demo.Service|99|7|3|SIMULATION|ling-a|trace-ctx|canary",
                            result);
                } finally {
                    executor.shutdownNow();
                }
            } finally {
                InvocationContext.detach(previous);
                parent.recycle();
            }
        }

        @Test
        @DisplayName("无父上下文时应创建空白子上下文")
        void shouldCreateEmptyChildContextWhenNoParentExists() throws Exception {
            Callable<Boolean> wrapped = InvocationContext.wrap(() -> {
                InvocationContext child = InvocationContext.current();
                assertNotNull(child);
                assertNull(child.getTraceId());
                assertEquals(InvocationExecutionMode.NORMAL, child.execution().getMode());
                return true;
            });

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                assertTrue(executor.submit(wrapped).get(5, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("包装后的 Runnable 执行完毕后应清理线程本地状态")
        void shouldCleanThreadLocalsAfterWrappedRunnableCompletes() throws Exception {
            InvocationContext parent = InvocationContext.obtain();
            parent.setTraceId("cleanup");
            InvocationContext previous = parent.attach();
            LingCallContext.setLingId("ling-cleanup");
            LingCallContext.setTraceId("trace-cleanup");

            try {
                CompletableFuture<InvocationContext> childCurrent = new CompletableFuture<>();
                CompletableFuture<String> childCallContext = new CompletableFuture<>();

                Runnable wrapped = InvocationContext.wrap(() -> assertNotNull(InvocationContext.current()));

                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    executor.submit(() -> {
                        wrapped.run();
                        childCurrent.complete(InvocationContext.current());
                        childCallContext.complete(String.valueOf(LingCallContext.getLingId())
                                + "|" + String.valueOf(LingCallContext.getTraceId()));
                    }).get(5, TimeUnit.SECONDS);

                    assertNull(childCurrent.get(5, TimeUnit.SECONDS));
                    assertEquals("null|null", childCallContext.get(5, TimeUnit.SECONDS));
                } finally {
                    executor.shutdownNow();
                }
            } finally {
                InvocationContext.detach(previous);
                parent.recycle();
            }
        }
    }
}
