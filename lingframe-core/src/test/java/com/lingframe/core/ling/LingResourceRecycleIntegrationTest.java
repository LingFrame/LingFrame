package com.lingframe.core.ling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LingUnloadCoordinator → DefaultLingResourceManager 编排缝的真实对象集成测试（非 mock 串联）。
 *
 * <p>覆盖本方案 P3 的核心验收：N 个孤儿资源卸载后全部 close 且逆注册序、多版本滚动更新时
 * 旧版本孤儿随版本卸载即时释放不累积。用例均使用真实 {@link DefaultLingResourceManager}
 * 与真实 {@link LingUnloadCoordinator}，不引入 Spring，守住 core 分层纪律。
 *
 * <p>说明：Spring 管理的 AutoCloseable Bean 由 Spring 容器负责关闭，本层仅注册“孤儿资源”
 * （非 Spring 管理的对象），架构上不存在二次关闭——该职责分界见 ling-autocloseable-recycle-plan.md §7，
 * 无需在本 core 测试引入 Spring。
 */
@DisplayName("资源回收编排缝集成测试")
class LingResourceRecycleIntegrationTest {

    private String lingId = "ling-it";
    private String v1 = "1.0.0";
    private String v2 = "2.0.0";

    private DefaultLingResourceManager newResourceManager() {
        return new DefaultLingResourceManager(null, null, null);
    }

    private LingUnloadCoordinator newCoordinator(DefaultLingResourceManager rm) {
        return new LingUnloadCoordinator(
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                rm,
                null);
    }

    private AutoCloseable tracking(String name, List<String> order) {
        return () -> order.add(name);
    }

    @Nested
    @DisplayName("N 个孤儿资源版本卸载")
    class SingleVersion {

        @Test
        @DisplayName("所有 N 个孤儿资源按逆注册序关闭")
        void closesAllOrphansInReverseOrder() {
            List<String> order = new ArrayList<>();
            DefaultLingResourceManager rm = newResourceManager();
            LingUnloadCoordinator coordinator = newCoordinator(rm);

            rm.registerCloseable(lingId, v1, tracking("r1", order));
            rm.registerCloseable(lingId, v1, tracking("r2", order));
            rm.registerCloseable(lingId, v1, tracking("r3", order));

            coordinator.onVersionUnload(lingId, v1, new ClassLoader() {});

            assertEquals(Arrays.asList("r3", "r2", "r1"), order,
                    "全部 N 个孤儿资源应被 close，且逆注册序");
            rm.shutdown();
        }

        @Test
        @DisplayName("单个 close 抛出异常时其余仍被关闭，卸载不中断")
        void singleFailureDoesNotBlockOthers() {
            List<String> order = new ArrayList<>();
            DefaultLingResourceManager rm = newResourceManager();
            LingUnloadCoordinator coordinator = newCoordinator(rm);

            rm.registerCloseable(lingId, v1, new AutoCloseable() {
                @Override
                public void close() {
                    order.add("boom");
                    throw new IllegalStateException("boom");
                }
            });
            rm.registerCloseable(lingId, v1, tracking("after", order));

            // 不应抛出
            assertDoesNotThrow(() -> coordinator.onVersionUnload(lingId, v1, new ClassLoader() {}));

            // 逆序：boom 先于 after 注册，逆序关闭时应先关 after
            assertEquals(Arrays.asList("after", "boom"), order);
            rm.shutdown();
        }
    }

    @Nested
    @DisplayName("多版本滚动更新")
    class RollingUpdate {

        @Test
        @DisplayName("旧版本孤儿随版本卸载即时释放，新版本孤儿不受影响、不累积")
        void oldVersionReleasedAtVersionUnload() {
            List<String> order = new ArrayList<>();
            DefaultLingResourceManager rm = newResourceManager();
            LingUnloadCoordinator coordinator = newCoordinator(rm);

            // v1 与 v2 并存（多版本滚动），各自注册孤儿资源
            rm.registerCloseable(lingId, v1, tracking("v1-r1", order));
            rm.registerCloseable(lingId, v1, tracking("v1-r2", order));
            rm.registerCloseable(lingId, v2, tracking("v2-r1", order));
            rm.registerCloseable(lingId, v2, tracking("v2-r2", order));

            // 滚动更新：卸载旧版本 v1
            coordinator.onVersionUnload(lingId, v1, new ClassLoader() {});

            // 仅 v1 的孤儿被关闭（逆序），v2 不受影响
            assertEquals(Arrays.asList("v1-r2", "v1-r1"), order);
            assertTrue(order.stream().noneMatch(s -> s.startsWith("v2-")),
                    "v2 孤儿不得被 v1 卸载误关");

            // 新版本 v2 继续存活，其孤儿资源仍留存，不累积释放
            List<String> before = new ArrayList<>(order);
            coordinator.onVersionUnload(lingId, v2, new ClassLoader() {});
            assertEquals(Arrays.asList("v1-r2", "v1-r1", "v2-r2", "v2-r1"), order,
                    "v2 卸载时再关闭其孤儿，此前 v1 的已释放不重复 close");
            assertNotEquals(before, order, "v2 卸载应新增 v2 资源的 close");

            rm.shutdown();
        }

        @Test
        @DisplayName("版本级卸载后整 Ling 卸载兜底释放迟到注册（有界留存不丢失）")
        void lateRegistrationFallbackOnLingClose() {
            List<String> order = new ArrayList<>();
            DefaultLingResourceManager rm = newResourceManager();
            LingUnloadCoordinator coordinator = newCoordinator(rm);

            // close 执行期间"迟到"的同版本注册（时序上表现为版本卸载后同版本再注册）
            rm.registerCloseable(lingId, v1, tracking("early", order));
            coordinator.onVersionUnload(lingId, v1, new ClassLoader() {});
            rm.registerCloseable(lingId, v1, tracking("late", order));

            // 整 Ling 卸载兜底释放所有残留
            coordinator.onLingUnload(lingId);

            assertTrue(order.contains("early"));
            assertTrue(order.contains("late"));
            rm.shutdown();
        }

        @Test
        @DisplayName("整 Ling 卸载清理全部版本残留（无 Spring 二次关闭担忧）")
        void lingCloseReleasesAllVersions() {
            List<String> order = new ArrayList<>();
            DefaultLingResourceManager rm = newResourceManager();
            LingUnloadCoordinator coordinator = newCoordinator(rm);

            rm.registerCloseable(lingId, v1, tracking("v1-r", order));
            rm.registerCloseable(lingId, v2, tracking("v2-r", order));

            coordinator.onLingUnload(lingId);

            assertTrue(order.contains("v1-r"));
            assertTrue(order.contains("v2-r"));
            rm.shutdown();
        }
    }
}