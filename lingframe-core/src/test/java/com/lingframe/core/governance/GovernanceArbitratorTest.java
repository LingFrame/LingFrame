package com.lingframe.core.governance;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.GovernancePolicyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GovernanceArbitrator 单元测试")
class GovernanceArbitratorTest {

    @Mock
    private GovernancePolicyProvider provider1;
    @Mock
    private GovernancePolicyProvider provider2;
    @Mock
    private LingRuntime runtime;

    private Method method;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        method = String.class.getMethod("toString");
    }

    @Nested
    @DisplayName("仲裁逻辑")
    class ArbitrationLogicTests {

        @Test
        @DisplayName("首个有效 Provider 应胜出 (First Win)")
        void testArbitrate_FirstProviderWins() {
            when(provider1.getOrder()).thenReturn(10);
            when(provider2.getOrder()).thenReturn(20);

            GovernanceDecision decision1 = GovernanceDecision.builder()
                    .requiredPermission("perm1")
                    .build();
            when(provider1.resolve(any(), any(), any())).thenReturn(decision1);

            GovernanceArbitrator arbitrator = new GovernanceArbitrator(Arrays.asList(provider1, provider2));
            GovernanceDecision result = arbitrator.arbitrate(runtime, method, null);

            assertEquals("perm1", result.getRequiredPermission());
        }

        @Test
        @DisplayName("首个 Provider 无决策时应回退到第二个")
        void testArbitrate_FallbackToSecondProvider() {
            when(provider1.getOrder()).thenReturn(10);
            when(provider2.getOrder()).thenReturn(20);

            // Provider 1 返回 null (无决策)
            when(provider1.resolve(any(), any(), any())).thenReturn(null);

            GovernanceDecision decision2 = GovernanceDecision.builder()
                    .requiredPermission("perm2")
                    .build();
            when(provider2.resolve(any(), any(), any())).thenReturn(decision2);

            GovernanceArbitrator arbitrator = new GovernanceArbitrator(Arrays.asList(provider1, provider2));
            GovernanceDecision result = arbitrator.arbitrate(runtime, method, null);

            assertEquals("perm2", result.getRequiredPermission());
        }

        @Test
        @DisplayName("无任何 Provider 决策时应使用默认策略")
        void testArbitrate_DefaultFallback() {
            when(provider1.getOrder()).thenReturn(10);
            when(provider1.resolve(any(), any(), any())).thenReturn(null);

            GovernanceArbitrator arbitrator = new GovernanceArbitrator(Collections.singletonList(provider1));
            GovernanceDecision result = arbitrator.arbitrate(runtime, method, null);

            assertNotNull(result);
            assertEquals("default:execute", result.getRequiredPermission());
            assertEquals(AccessType.EXECUTE, result.getAccessType());
            assertFalse(result.getAuditEnabled());
        }
    }
}
