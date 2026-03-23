package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
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
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Nested
    @DisplayName("快速失败")
    class FailFastTests {

        @Test
        @DisplayName("方法无法解析且缺少入口治理事实时应快速失败")
        void shouldFailWhenMethodCannotBeResolvedWithoutEntryGovernanceFacts() {
            GovernanceDecisionFilter filter = new GovernanceDecisionFilter(repository,
                    new GovernanceArbitrator(Collections.emptyList()));

            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:" + TestService.class.getName());
            context.setTargetLingId("ling1");
            context.setMethodName("ping");

            try {
                LingInvocationException exception = assertThrows(LingInvocationException.class,
                        () -> filter.doFilter(context, current -> null));
                assertEquals(LingInvocationException.ErrorKind.INTERNAL_ERROR, exception.getKind());
            } finally {
                context.recycle();
            }
        }

        @Test
        @DisplayName("仲裁器无决策且缺少入口治理事实时应快速失败")
        void shouldFailWhenArbitratorReturnsNoDecisionWithoutEntryGovernanceFacts() {
            GovernanceDecisionFilter filter = new GovernanceDecisionFilter(repository,
                    new GovernanceArbitrator(Collections.singletonList(emptyProvider())));
            repository.register(new LingRuntime("ling1", null, null, new RuntimeCoordinator(null)));

            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:" + TestService.class.getName());
            context.setTargetLingId("ling1");
            context.setMethodName("ping");
            context.setParameterTypeNames(new String[0]);
            context.resolution().setTargetClassName(TestService.class.getName());
            context.resolution().setTargetClassLoader(TestService.class.getClassLoader());

            try {
                LingInvocationException exception = assertThrows(LingInvocationException.class,
                        () -> filter.doFilter(context, current -> null));
                assertEquals(LingInvocationException.ErrorKind.INTERNAL_ERROR, exception.getKind());
            } finally {
                context.recycle();
            }
        }

        @Test
        @DisplayName("预填充入口治理事实时应允许继续执行")
        void shouldAllowPrePopulatedEntryGovernanceFacts() throws Throwable {
            GovernanceDecisionFilter filter = new GovernanceDecisionFilter(repository,
                    new GovernanceArbitrator(Collections.emptyList()));

            InvocationContext context = InvocationContext.obtain();
            context.setServiceFQSID("ling1:http");
            context.setTargetLingId("ling1");
            context.setRequiredPermission("web:read");
            context.setAccessType(AccessType.READ);

            try {
                Object result = filter.doFilter(context, current -> "ok");
                assertEquals("ok", result);
                assertEquals("web:read", context.getRequiredPermission());
                assertEquals(AccessType.READ, context.getAccessType());
            } finally {
                context.recycle();
            }
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

    private GovernancePolicyProvider emptyProvider() {
        return new GovernancePolicyProvider() {
            @Override
            public int getOrder() {
                return 1;
            }

            @Override
            public GovernanceDecision resolve(LingRuntime runtime, Method method, InvocationContext ctx) {
                return GovernanceDecision.empty();
            }
        };
    }
}
