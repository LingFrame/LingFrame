package com.lingframe.example.saas.inventory.service.impl;

import com.lingframe.example.mall.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.awaitility.Awaitility;
import java.util.concurrent.TimeUnit;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * {@link InventoryHoldServiceImpl} 单元测试。
 * <p>
 * 覆盖全部分支：预占成功/失败、确认扣减、释放、超时自动释放（短 TTL 真触发）、destroy 资源清理。
 * <p>
 * coreInventoryService 用 Mockito mock，通过包私有构造器直接传入，无反射。
 * 每个测试方法创建新实例并在 finally 中 destroy，避免 scheduler 线程跨测试泄漏。
 */
@DisplayName("InventoryHoldServiceImpl 单元测试")
class InventoryHoldServiceImplTest {

    @Nested
    @DisplayName("holdStock 预占")
    class HoldStockTest {

        @Test
        @DisplayName("预占成功：灵核 lockStock 返回 true，holdId 非空，状态 HOLDING")
        void holdSuccess() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(1L, 5)).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 5, 60);
                assertNotNull(holdId, "预占成功应返回 holdId");
                assertEquals("HOLDING", impl.getHoldStatus(holdId), "预占后状态应为 HOLDING");
                verify(core).lockStock(1L, 5);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("库存不足：灵核 lockStock 返回 false，返回 null")
        void holdFailInsufficientStock() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(false);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 5, 60);
                assertNull(holdId, "库存不足应返回 null");
                verify(core).lockStock(1L, 5);
                verifyNoMoreInteractions(core);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("参数校验：count 为 null 抛 IllegalArgumentException")
        void nullCount() {
            InventoryService core = mock(InventoryService.class);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                assertThrows(IllegalArgumentException.class, () -> impl.holdStock(1L, null, 60));
                verifyNoInteractions(core);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("参数校验：count <= 0 抛 IllegalArgumentException")
        void zeroCount() {
            InventoryService core = mock(InventoryService.class);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                assertThrows(IllegalArgumentException.class, () -> impl.holdStock(1L, 0, 60));
                verifyNoInteractions(core);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("参数校验：ttlSeconds <= 0 抛 IllegalArgumentException")
        void zeroTtl() {
            InventoryService core = mock(InventoryService.class);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                assertThrows(IllegalArgumentException.class, () -> impl.holdStock(1L, 5, 0));
                verifyNoInteractions(core);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("参数校验：ttlSeconds 超过上限（3600s）抛 IllegalArgumentException")
        void ttlExceedsMax() {
            InventoryService core = mock(InventoryService.class);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                assertThrows(IllegalArgumentException.class, () -> impl.holdStock(1L, 5, 3601));
                verifyNoInteractions(core);
            } finally {
                impl.destroy();
            }
        }
    }

    @Nested
    @DisplayName("confirmDeduct 确认扣减")
    class ConfirmDeductTest {

        @Test
        @DisplayName("确认成功：HOLDING → 灵核 deductLockedStock true → CONFIRMED")
        void confirmSuccess() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.deductLockedStock(any(), anyInt())).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 5, 60);
                assertTrue(impl.confirmDeduct(holdId), "确认扣减应成功");
                assertEquals("CONFIRMED", impl.getHoldStatus(holdId), "确认后状态应为 CONFIRMED");
                verify(core).deductLockedStock(1L, 5);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("单据不存在：返回 false")
        void confirmNotFound() {
            InventoryService core = mock(InventoryService.class);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                assertFalse(impl.confirmDeduct("NOT_EXIST"), "不存在的单据应返回 false");
                verifyNoInteractions(core);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("状态非 HOLDING（已确认）：返回 false，不调灵核")
        void confirmAlreadyConfirmed() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.deductLockedStock(any(), anyInt())).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 5, 60);
                impl.confirmDeduct(holdId);
                assertFalse(impl.confirmDeduct(holdId), "重复确认应返回 false");
                verify(core, times(1)).deductLockedStock(any(), anyInt());
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("灵核扣减失败：deductLockedStock 返回 false，确认失败，状态不变")
        void confirmCoreDeductFailed() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.deductLockedStock(any(), anyInt())).thenReturn(false);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 5, 60);
                assertFalse(impl.confirmDeduct(holdId), "灵核扣减失败应返回 false");
                assertEquals("HOLDING", impl.getHoldStatus(holdId), "失败后状态应保持 HOLDING");
            } finally {
                impl.destroy();
            }
        }
    }

    @Nested
    @DisplayName("releaseHold 释放")
    class ReleaseHoldTest {

        @Test
        @DisplayName("释放成功：HOLDING → 灵核 releaseStock true → RELEASED")
        void releaseSuccess() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.releaseStock(any(), anyInt())).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 5, 60);
                assertTrue(impl.releaseHold(holdId), "释放应成功");
                assertEquals("RELEASED", impl.getHoldStatus(holdId), "释放后状态应为 RELEASED");
                verify(core).releaseStock(1L, 5);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("单据不存在：返回 false")
        void releaseNotFound() {
            InventoryService core = mock(InventoryService.class);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                assertFalse(impl.releaseHold("NOT_EXIST"), "不存在的单据应返回 false");
                verifyNoInteractions(core);
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("状态非 HOLDING（已确认）：返回 false")
        void releaseAlreadyConfirmed() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.deductLockedStock(any(), anyInt())).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 5, 60);
                impl.confirmDeduct(holdId);
                assertFalse(impl.releaseHold(holdId), "释放已确认单据应返回 false");
                verify(core, never()).releaseStock(any(), anyInt());
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("灵核释放失败：releaseStock 返回 false，释放失败")
        void releaseCoreFailed() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.releaseStock(any(), anyInt())).thenReturn(false);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 5, 60);
                assertFalse(impl.releaseHold(holdId), "灵核释放失败应返回 false");
                assertEquals("HOLDING", impl.getHoldStatus(holdId), "失败后状态应保持 HOLDING");
            } finally {
                impl.destroy();
            }
        }
    }

    @Nested
    @DisplayName("autoExpire 超时自动释放（短 TTL 真触发）")
    class AutoExpireTest {

        @Test
        @DisplayName("TTL 到期后自动释放：灵核 releaseStock 成功 → 状态变 EXPIRED")
        void autoExpireToExpired() throws InterruptedException {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.releaseStock(any(), anyInt())).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 3, 1);
                // 使用 Awaitility 替代死等，任务执行完毕瞬间即通过测试
                Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertEquals("EXPIRED", impl.getHoldStatus(holdId), "TTL 到期后状态应为 EXPIRED");
                    verify(core).releaseStock(1L, 3);
                });
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("TTL 到期后灵核释放失败：状态变 RELEASED")
        void autoExpireReleaseFailed() throws InterruptedException {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.releaseStock(any(), anyInt())).thenReturn(false);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            try {
                String holdId = impl.holdStock(1L, 3, 1);
                Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertEquals("RELEASED", impl.getHoldStatus(holdId),
                            "灵核释放失败时状态应为 RELEASED");
                    verify(core).releaseStock(1L, 3);
                });
            } finally {
                impl.destroy();
            }
        }

        @Test
        @DisplayName("非 HOLDING 单据不触发自动释放：确认扣减后 autoExpire 无操作")
        void autoExpireSkippedForNonHolding() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.deductLockedStock(any(), anyInt())).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            
            // 注入 Mock Scheduler 彻底消除时钟依赖与 CI 环境的竞态
            ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
            ReflectionTestUtils.setField(impl, "scheduler", mockScheduler);
            
            try {
                String holdId = impl.holdStock(1L, 3, 1);
                
                // 捕获提交的延迟任务
                ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
                verify(mockScheduler).schedule(taskCaptor.capture(), anyLong(), any());
                
                impl.confirmDeduct(holdId);
                assertEquals("CONFIRMED", impl.getHoldStatus(holdId));
                
                // 立即手动执行延迟任务，验证其遇到非 HOLDING 状态时会直接短路退出
                taskCaptor.getValue().run();
                
                assertEquals("CONFIRMED", impl.getHoldStatus(holdId), "已确认单据状态不应被 autoExpire 改变");
                verify(core, never()).releaseStock(any(), anyInt());
            } finally {
                impl.destroy();
            }
        }
    }

    @Nested
    @DisplayName("destroy 资源清理（灵元卸载安全）")
    class DestroyTest {

        @Test
        @DisplayName("destroy 执行补偿释放未决的 HOLDING 库存，并清空 holdRecords")
        void destroyClearsRecords() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            when(core.releaseStock(any(), anyInt())).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            String holdId = impl.holdStock(1L, 5, 60);
            assertEquals("HOLDING", impl.getHoldStatus(holdId));

            impl.destroy();

            verify(core).releaseStock(1L, 5); // 验证触发了补偿释放
            assertEquals("NOT_FOUND", impl.getHoldStatus(holdId),
                    "destroy 后预占记录应已清空，查询返回 NOT_FOUND");
        }

        @Test
        @DisplayName("destroy 后 scheduler 已终止：新预占抛 IllegalStateException（包 RejectedExecutionException）")
        void destroyTerminatesScheduler() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(any(), anyInt())).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            impl.destroy();

            // scheduler 已 shutdown，schedule 抛 RejectedExecutionException，holdStock 捕获后回滚并包成 IllegalStateException
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> impl.holdStock(1L, 5, 60),
                    "destroy 后 scheduler 已终止，新预占应抛 IllegalStateException");
            assertInstanceOf(RejectedExecutionException.class, ex.getCause(),
                    "底层应为 RejectedExecutionException");
        }

        @Test
        @DisplayName("scheduler 拒收任务时回滚：核心库存已释放、预占单未残留")
        void holdRolledBackWhenSchedulerRejects() {
            InventoryService core = mock(InventoryService.class);
            when(core.lockStock(1L, 5)).thenReturn(true);
            when(core.releaseStock(1L, 5)).thenReturn(true);
            InventoryHoldServiceImpl impl = new InventoryHoldServiceImpl(core);
            impl.destroy(); // 让 scheduler 进入终止态，下一次 schedule 即抛 RejectedExecutionException

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> impl.holdStock(1L, 5, 60),
                    "scheduler 终止后预占应抛 IllegalStateException");

            // 回滚断言：核心库存释放被调用、预占单不残留
            verify(core).lockStock(1L, 5);
            verify(core).releaseStock(1L, 5);
            assertInstanceOf(RejectedExecutionException.class, ex.getCause(),
                    "底层应为 RejectedExecutionException");
        }
    }
}
