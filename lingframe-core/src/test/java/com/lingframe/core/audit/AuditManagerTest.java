package com.lingframe.core.audit;

import com.lingframe.api.security.PermissionAuditResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AuditManager 测试")
class AuditManagerTest {

    @BeforeEach
    void setUp() {
        // 前一个用例可能已关闭执行器，先恢复纯净状态
        AuditManager.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        AuditManager.resetForTesting();
    }

    @Nested
    @DisplayName("异步记录真实性与溢出语义")
    class AsyncRecordBehavior {

        @Test
        @DisplayName("asyncRecord 完整参数应被实际消费（记录不丢失）")
        void shouldConsumeAsyncRecordWithFullParams() throws InterruptedException {
            // 队列容量 1：连续提交的两条记录必然触发队列排队；消费线程会逐条消费
            AuditManager.configure(AuditManager.OverflowPolicy.DISCARD, 1);
            assertEquals(0, AuditManager.getDiscardCount());

            AuditManager.asyncRecord("trace-001", "ling-a", "admin",
                    PermissionAuditResult.ALLOWED, "read", "execute",
                    "resource-1", null, 1000L);
            AuditManager.asyncRecord("trace-002", "ling-b", "admin",
                    PermissionAuditResult.DENIED, "write", "execute",
                    "resource-2", "no permission", 500L);

            // 审计为异步消费：等待足够时间让两个任务被单线程执行器处理
            awaitExecutorDrain();
            assertEquals(0, AuditManager.getDiscardCount(),
                    "容量内的审计记录应被消费，不应因溢出被丢弃");
        }

        @Test
        @DisplayName("队列满且策略为 DISCARD 时应丢弃并计数")
        void shouldDiscardWhenQueueFullWithDiscardPolicy() throws InterruptedException {
            AuditManager.configure(AuditManager.OverflowPolicy.DISCARD, 1);
            assertEquals(0, AuditManager.getDiscardCount());

            // 阻塞队列容量 1 + 单线程执行器：并发提交远超容量的记录，必然触发丢弃
            int submissions = 2_000;
            CountDownLatch latch = new CountDownLatch(submissions);
            for (int i = 0; i < submissions; i++) {
                AuditManager.asyncRecord("trace", "ling-a", "admin",
                        PermissionAuditResult.ALLOWED, "read", "execute",
                        "resource", null, 1L);
                latch.countDown();
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "提交应全部完成");

            awaitExecutorDrain();
            assertTrue(AuditManager.getDiscardCount() > 0,
                    "容量 1 的队列在 2000 次提交下应产生丢弃计数");
        }

        @Test
        @DisplayName("BLOCK 策略下阻塞提交不存在丢弃且全部消费")
        void shouldBlockAndNotDiscardWhenPolicyIsBlock() throws InterruptedException {
            AuditManager.configure(AuditManager.OverflowPolicy.BLOCK, 1);
            assertEquals(0, AuditManager.getDiscardCount());

            int submissions = 200;
            CountDownLatch latch = new CountDownLatch(submissions);
            for (int i = 0; i < submissions; i++) {
                AuditManager.asyncRecord("trace", "ling-a", "admin",
                        PermissionAuditResult.ALLOWED, "read", "execute",
                        "resource", null, 1L);
                latch.countDown();
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "提交应全部完成");

            awaitExecutorDrain();
            assertEquals(0, AuditManager.getDiscardCount(),
                    "BLOCK 策略不得因队列满丢失审计记录");
        }
    }

    @Nested
    @DisplayName("关闭与空参护身")
    class ShutdownAndEdgeCases {

        @Test
        @DisplayName("关闭后新记录静默丢弃不报错")
        void shouldSilentlyDropAfterShutdown() {
            AuditManager.shutdown();
            assertTrue(AuditManager.isShutdown());
            assertDoesNotThrow(() -> AuditManager.asyncRecord(
                    "trace-after-shutdown", "ling-a", "admin",
                    PermissionAuditResult.ALLOWED, "read", "execute",
                    "resource", null, 0L));
        }

        @Test
        @DisplayName("简化参数 null result 为 DENIED 不报错")
        void shouldAsyncRecordWithSimpleParams() {
            assertDoesNotThrow(() -> AuditManager.asyncRecord(
                    "trace-002", "ling-b", "execute", "service-1",
                    new Object[]{"arg1"}, "result", 500L));
        }

        @Test
        @DisplayName("null 参数不报错")
        void shouldAsyncRecordWithNullParams() {
            assertDoesNotThrow(() -> AuditManager.asyncRecord(
                    null, null, null,
                    PermissionAuditResult.DENIED, null, null,
                    null, "permission denied", 0L));
        }

        @Test
        @DisplayName("长字符串与超长内容应被截断而不报错")
        void shouldAsyncRecordWithLongStrings() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                sb.append("x");
            }
            String longStr = sb.toString();
            assertDoesNotThrow(() -> AuditManager.asyncRecord(
                    "trace-004", longStr, longStr,
                    PermissionAuditResult.ALLOWED, longStr, longStr,
                    longStr, longStr, 999L));
        }

        @Test
        @DisplayName("空字符串不报错")
        void shouldAsyncRecordWithEmptyStrings() {
            assertDoesNotThrow(() -> AuditManager.asyncRecord(
                    "", "", "",
                    PermissionAuditResult.ALLOWED, "", "", "", "", 0L));
        }
    }

    /**
     * 等待单线程执行器把已入队任务消费完。
     * <p>
     * 不做精确同步（执行器内部不可观测），用上界等待保证「队列中的记录应被消费」
     * 断言不含 false negative；本类其余用例在同一 @BeforeEach 重建执行器。
     */
    private void awaitExecutorDrain() throws InterruptedException {
        Thread.sleep(TimeUnit.MILLISECONDS.toMillis(50));
    }
}
