package com.lingframe.core.pipeline;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GovernanceDecisionFilter 测试")
class GovernanceDecisionFilterTest {

    static class TestService {
        public String ping() {
            return "pong";
        }
    }

    private final LingRepository repository = new DefaultLingRepository();

    @Nested
    @DisplayName("治理决策写回")
    class DecisionApplyTests {

        @Test
        @DisplayName("应将治理决策写入上下文治理状态")
        void shouldApplyDecisionToGovernanceState() throws Throwable {
            GovernanceDecisionFilter filter = new GovernanceDecisionFilter(repository, buildArbitrator(1234));
            repository.register(new LingRuntime("ling1", null, null, new RuntimeCoordinator(null)));

            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:" + TestService.class.getName());
            context.setTargetLingId("ling1");
            context.setMethodName("ping");
            context.resolution().setTargetClassName(TestService.class.getName());
            context.resolution().setTargetClassLoader(TestService.class.getClassLoader());
            context.resolution().setResolvedParameterTypes(new Class<?>[0]);

            LingFilterChain chain = current -> null;
            filter.doFilter(context, chain);

            assertEquals("demo:ping", context.getRequiredPermission());
            assertEquals(AccessType.EXECUTE, context.getAccessType());
            assertEquals(true, context.isShouldAudit());
            assertEquals("PING_CALL", context.getAuditAction());
            assertEquals("TestPolicy", context.getRuleSource());
            assertEquals(Integer.valueOf(1234), context.governance().getTimeoutMs());

            context.recycle();
        }

        @Test
        @DisplayName("缺失已解析参数类型时应根据参数名反查方法")
        void shouldResolveMethodFromParameterNamesWhenResolutionTypesAreMissing() throws Throwable {
            GovernanceDecisionFilter filter = new GovernanceDecisionFilter(repository, buildArbitrator(100));
            repository.register(new LingRuntime("ling1", null, null, new RuntimeCoordinator(null)));

            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:" + TestService.class.getName());
            context.setTargetLingId("ling1");
            context.setMethodName("ping");
            context.setParameterTypeNames(new String[0]);
            context.resolution().setTargetClassName(TestService.class.getName());
            context.resolution().setTargetClassLoader(TestService.class.getClassLoader());

            LingFilterChain chain = current -> null;
            filter.doFilter(context, chain);

            assertEquals("demo:ping", context.getRequiredPermission());
            assertEquals(AccessType.EXECUTE, context.getAccessType());
            assertEquals(true, context.isShouldAudit());
            assertEquals("PING_CALL", context.getAuditAction());
            assertEquals("TestPolicy", context.getRuleSource());
            assertEquals(Integer.valueOf(100), context.governance().getTimeoutMs());
            assertEquals("ping", context.resolution().getResolvedMethod().getName());

            context.recycle();
        }
    }

    private GovernanceArbitrator buildArbitrator(long timeoutMs) {
        GovernancePolicyProvider provider = new GovernancePolicyProvider() {
            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public GovernanceDecision resolve(LingRuntime runtime, Method method, InvocationContext ctx) {
                return GovernanceDecision.builder()
                        .requiredPermission("demo:ping")
                        .accessType(AccessType.EXECUTE)
                        .auditEnabled(true)
                        .auditAction("PING_CALL")
                        .timeout(Duration.ofMillis(timeoutMs))
                        .source("TestPolicy")
                        .build();
            }
        };
        return new GovernanceArbitrator(Arrays.asList(provider));
    }
}
