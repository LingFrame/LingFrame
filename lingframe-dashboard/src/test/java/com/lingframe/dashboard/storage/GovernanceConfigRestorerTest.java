package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.router.CanaryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GovernanceConfigRestorer 单元测试
 * <p>
 * 通过 mock GovernanceStorage / LocalGovernanceRegistry / CanaryRouter 隔离下游依赖，
 * 使用真实 ObjectMapper 以验证 canary JSON 的真实解析路径。
 * 覆盖：空配置早退 / canary 成功与异常 / invocation 成功与异常 / 合并多 patch /
 * 多 lingId / 外层异常兜底 / 边界值（percent=0、canaryVersion=null）等所有分支。
 */
@DisplayName("GovernanceConfigRestorer 单元测试")
class GovernanceConfigRestorerTest {

    private GovernanceStorage governanceStorage;
    private LocalGovernanceRegistry governanceRegistry;
    private CanaryRouter canaryRouter;
    private ObjectMapper objectMapper;
    private GovernanceConfigRestorer restorer;

    @BeforeEach
    void setUp() {
        governanceStorage = mock(GovernanceStorage.class);
        governanceRegistry = mock(LocalGovernanceRegistry.class);
        canaryRouter = mock(CanaryRouter.class);
        // 使用真实 ObjectMapper，让 canary JSON 解析走真实路径
        objectMapper = new ObjectMapper();
        restorer = new GovernanceConfigRestorer(governanceStorage, governanceRegistry,
                canaryRouter, objectMapper);
    }

    /** 辅助方法：构造单个 lingId 的内层配置 Map（保证插入顺序便于断言） */
    private Map<String, String> configs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 辅助方法：构造 allConfigs（外层 lingId -> 内层配置 Map） */
    private Map<String, Map<String, String>> allConfigs(String lingId, Map<String, String> inner) {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        all.put(lingId, inner);
        return all;
    }

    // ==================== 场景 1：空配置 ====================

    @Nested
    @DisplayName("空配置场景")
    class EmptyConfigsTests {

        @Test
        @DisplayName("allConfigs 为空时应直接返回，不调用任何下游")
        void shouldReturnEarlyWhenAllConfigsEmpty() {
            when(governanceStorage.loadAllConfigs()).thenReturn(new HashMap<>());

            assertDoesNotThrow(() -> restorer.restore());

            verify(canaryRouter, never())
                    .setCanaryConfig(anyString(), anyInt(), nullable(String.class));
            verify(governanceRegistry, never()).updatePatch(anyString(), any(GovernancePolicy.class));
        }
    }

    // ==================== canary 配置分支 ====================

    @Nested
    @DisplayName("canary 配置恢复")
    class CanaryRestoreTests {

        @Test
        @DisplayName("合法 canary JSON 应调用 setCanaryConfig")
        void shouldRestoreCanaryConfig() {
            // 场景 2：合法 canary 配置
            String canaryJson = "{\"percent\":50,\"canaryVersion\":\"v2\"}";
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-1", configs("canary", canaryJson)));

            restorer.restore();

            verify(canaryRouter).setCanaryConfig("ling-1", 50, "v2");
            verify(governanceRegistry, never())
                    .updatePatch(anyString(), any(GovernancePolicy.class));
        }

        @Test
        @DisplayName("canary JSON 解析失败应跳过该恢复（内层 try-catch）")
        void shouldSkipCanaryWhenJsonInvalid() {
            // 场景 3：JSON 解析失败
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-2", configs("canary", "not-a-valid-json")));

            assertDoesNotThrow(() -> restorer.restore());

            // canary 未恢复，invocation 也未配置，hasPatch=false
            verify(canaryRouter, never())
                    .setCanaryConfig(anyString(), anyInt(), nullable(String.class));
            verify(governanceRegistry, never())
                    .updatePatch(anyString(), any(GovernancePolicy.class));
        }

        @Test
        @DisplayName("canary 缺失 percent 字段应抛 NPE 被捕获，跳过该恢复")
        void shouldSkipCanaryWhenPercentMissing() {
            // 场景 4：percent 字段缺失，((Number) null).intValue() 抛 NPE
            String canaryJson = "{\"canaryVersion\":\"v2\"}";
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-3", configs("canary", canaryJson)));

            assertDoesNotThrow(() -> restorer.restore());

            verify(canaryRouter, never())
                    .setCanaryConfig(anyString(), anyInt(), nullable(String.class));
        }

        @Test
        @DisplayName("canary percent=0 仍应调用 setCanaryConfig")
        void shouldRestoreCanaryWithZeroPercent() {
            // 场景 10：percent=0 边界值
            String canaryJson = "{\"percent\":0,\"canaryVersion\":\"v1\"}";
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-4", configs("canary", canaryJson)));

            restorer.restore();

            verify(canaryRouter).setCanaryConfig("ling-4", 0, "v1");
        }

        @Test
        @DisplayName("canaryVersion 为 null 仍应调用 setCanaryConfig(lingId, percent, null)")
        void shouldRestoreCanaryWithNullVersion() {
            // 场景 11：canaryVersion 缺失为 null
            String canaryJson = "{\"percent\":30}";
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-5", configs("canary", canaryJson)));

            restorer.restore();

            verify(canaryRouter).setCanaryConfig("ling-5", 30, null);
        }
    }

    // ==================== invocation / permission 配置分支 ====================

    @Nested
    @DisplayName("治理策略配置恢复")
    class GovernancePolicyRestoreTests {

        @Test
        @DisplayName("合法 invocation 配置应调用 updatePatch")
        void shouldRestoreInvocationConfig() {
            // 场景 5：合法 invocation 配置
            String invocationJson = "{\"invocation\":{\"timeoutMs\":1000}}";
            GovernancePolicy policy = new GovernancePolicy();
            when(governanceStorage.safeDeserialize(invocationJson)).thenReturn(policy);
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-6", configs("invocation", invocationJson)));

            restorer.restore();

            verify(governanceRegistry).updatePatch(eq("ling-6"), any(GovernancePolicy.class));
            verify(canaryRouter, never())
                    .setCanaryConfig(anyString(), anyInt(), nullable(String.class));
        }

        @Test
        @DisplayName("safeDeserialize 抛异常应跳过该配置（内层 try-catch）")
        void shouldSkipInvocationWhenDeserializeFails() {
            // 场景 6：safeDeserialize 失败
            String badJson = "{bad}";
            when(governanceStorage.safeDeserialize(badJson))
                    .thenThrow(new RuntimeException("反序列化失败"));
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-7", configs("invocation", badJson)));

            assertDoesNotThrow(() -> restorer.restore());

            verify(governanceRegistry, never())
                    .updatePatch(anyString(), any(GovernancePolicy.class));
        }

        @Test
        @DisplayName("invocation + permission 多个配置应合并为单个 patch 后调用一次 updatePatch")
        void shouldMergeMultipleGovernancePolicies() {
            // 覆盖 mergedPatch != null 的 merge 分支
            String invocationJson = "{\"invocation\":{\"timeoutMs\":1000}}";
            String permissionJson = "{\"permissions\":[]}";
            GovernancePolicy policy1 = new GovernancePolicy();
            GovernancePolicy policy2 = new GovernancePolicy();
            when(governanceStorage.safeDeserialize(invocationJson)).thenReturn(policy1);
            when(governanceStorage.safeDeserialize(permissionJson)).thenReturn(policy2);
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-merge",
                            configs("invocation", invocationJson, "permission", permissionJson)));

            restorer.restore();

            // safeDeserialize 被调用两次，updatePatch 仅一次（合并后）
            verify(governanceStorage, times(2)).safeDeserialize(anyString());
            verify(governanceRegistry).updatePatch(eq("ling-merge"), any(GovernancePolicy.class));
        }
    }

    // ==================== 组合场景 ====================

    @Nested
    @DisplayName("组合场景")
    class CombinationTests {

        @Test
        @DisplayName("canary + invocation 同时存在应两者都恢复")
        void shouldRestoreBothCanaryAndInvocation() {
            // 场景 7：两者同时存在
            String canaryJson = "{\"percent\":50,\"canaryVersion\":\"v2\"}";
            String invocationJson = "{\"invocation\":{\"timeoutMs\":500}}";
            when(governanceStorage.safeDeserialize(invocationJson)).thenReturn(new GovernancePolicy());
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-both",
                            configs("canary", canaryJson, "invocation", invocationJson)));

            restorer.restore();

            verify(canaryRouter).setCanaryConfig("ling-both", 50, "v2");
            verify(governanceRegistry).updatePatch(eq("ling-both"), any(GovernancePolicy.class));
        }

        @Test
        @DisplayName("只有 canary 没有 invocation/permission 应 hasPatch=true")
        void shouldRestoreCanaryOnly() {
            // 场景 12：仅 canary
            String canaryJson = "{\"percent\":100,\"canaryVersion\":\"v9\"}";
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-canary-only", configs("canary", canaryJson)));

            restorer.restore();

            verify(canaryRouter).setCanaryConfig("ling-canary-only", 100, "v9");
            verify(governanceRegistry, never())
                    .updatePatch(anyString(), any(GovernancePolicy.class));
        }

        @Test
        @DisplayName("只有 invocation 没有 canary 应 hasPatch=true")
        void shouldRestoreInvocationOnly() {
            // 场景 13：仅 invocation
            String invocationJson = "{\"invocation\":{\"rateLimitPerSecond\":10}}";
            when(governanceStorage.safeDeserialize(invocationJson)).thenReturn(new GovernancePolicy());
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-inv-only",
                            configs("invocation", invocationJson)));

            restorer.restore();

            verify(governanceRegistry).updatePatch(eq("ling-inv-only"), any(GovernancePolicy.class));
            verify(canaryRouter, never())
                    .setCanaryConfig(anyString(), anyInt(), nullable(String.class));
        }

        @Test
        @DisplayName("canary 与 invocation 全部失败应 hasPatch=false，restored 不增")
        void shouldNotRestoreWhenAllConfigsFail() {
            // 场景 14：所有项都失败
            String badCanaryJson = "{broken";
            String badInvocationJson = "{also-broken";
            when(governanceStorage.safeDeserialize(badInvocationJson))
                    .thenThrow(new RuntimeException("失败"));
            when(governanceStorage.loadAllConfigs())
                    .thenReturn(allConfigs("ling-all-fail",
                            configs("canary", badCanaryJson, "invocation", badInvocationJson)));

            assertDoesNotThrow(() -> restorer.restore());

            verify(canaryRouter, never())
                    .setCanaryConfig(anyString(), anyInt(), nullable(String.class));
            verify(governanceRegistry, never())
                    .updatePatch(anyString(), any(GovernancePolicy.class));
        }

        @Test
        @DisplayName("多个 lingId 应各自独立处理")
        void shouldRestoreMultipleLingIds() {
            // 场景 8：多个 lingId
            String canaryJson = "{\"percent\":20,\"canaryVersion\":\"va\"}";
            String invocationJson = "{\"invocation\":{\"timeoutMs\":200}}";
            when(governanceStorage.safeDeserialize(invocationJson)).thenReturn(new GovernancePolicy());

            Map<String, Map<String, String>> all = new LinkedHashMap<>();
            all.put("ling-a", configs("canary", canaryJson));
            all.put("ling-b", configs("invocation", invocationJson));
            when(governanceStorage.loadAllConfigs()).thenReturn(all);

            restorer.restore();

            verify(canaryRouter).setCanaryConfig("ling-a", 20, "va");
            verify(governanceRegistry).updatePatch(eq("ling-b"), any(GovernancePolicy.class));
        }
    }

    // ==================== 外层异常兜底 ====================

    @Nested
    @DisplayName("外层异常兜底")
    class OuterExceptionTests {

        @Test
        @DisplayName("loadAllConfigs 抛异常不应向外传播（外层 try-catch 兜底）")
        void shouldNotThrowWhenLoadAllConfigsFails() {
            // 场景 9：外层异常被捕获
            when(governanceStorage.loadAllConfigs())
                    .thenThrow(new RuntimeException("存储层异常"));

            assertDoesNotThrow(() -> restorer.restore());

            verify(canaryRouter, never())
                    .setCanaryConfig(anyString(), anyInt(), nullable(String.class));
            verify(governanceRegistry, never())
                    .updatePatch(anyString(), any(GovernancePolicy.class));
        }
    }
}
