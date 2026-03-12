package com.lingframe.core.pipeline;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GovernanceDecisionFilterTest {

    static class TestService {
        public String ping() {
            return "pong";
        }
    }

    @Test
    void appliesDecisionToInvocationContext() throws Throwable {
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
                        .timeout(Duration.ofMillis(1234))
                        .source("TestPolicy")
                        .build();
            }
        };

        GovernanceArbitrator arbitrator = new GovernanceArbitrator(List.of(provider));
        LingRepository repository = new DefaultLingRepository();
        repository.register(new LingRuntime("ling1", null, null));

        GovernanceDecisionFilter filter = new GovernanceDecisionFilter(repository, arbitrator);

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:" + TestService.class.getName());
        ctx.setTargetLingId("ling1");
        ctx.setMethodName("ping");
        ctx.getAttachments().put("ling.target.className", TestService.class.getName());
        ctx.getAttachments().put("ling.resolved.types", new Class<?>[0]);

        LingFilterChain chain = c -> null;
        filter.doFilter(ctx, chain);

        assertEquals("demo:ping", ctx.getRequiredPermission());
        assertEquals(AccessType.EXECUTE, ctx.getAccessType());
        assertEquals(true, ctx.isShouldAudit());
        assertEquals("PING_CALL", ctx.getAuditAction());
        assertEquals("TestPolicy", ctx.getRuleSource());
        assertEquals(Integer.valueOf(1234), ctx.getTimeout());

        ctx.recycle();
    }

    @Test
    void appliesDecisionWithoutResolvedTypes() throws Throwable {
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
                        .timeout(Duration.ofMillis(100))
                        .source("TestPolicy")
                        .build();
            }
        };

        GovernanceArbitrator arbitrator = new GovernanceArbitrator(List.of(provider));
        LingRepository repository = new DefaultLingRepository();
        repository.register(new LingRuntime("ling1", null, null));

        GovernanceDecisionFilter filter = new GovernanceDecisionFilter(repository, arbitrator);

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:" + TestService.class.getName());
        ctx.setTargetLingId("ling1");
        ctx.setMethodName("ping");
        ctx.setParameterTypeNames(new String[0]);
        ctx.getAttachments().put("ling.target.className", TestService.class.getName());

        LingFilterChain chain = c -> null;
        filter.doFilter(ctx, chain);

        assertEquals("demo:ping", ctx.getRequiredPermission());
        assertEquals(AccessType.EXECUTE, ctx.getAccessType());
        assertEquals(true, ctx.isShouldAudit());
        assertEquals("PING_CALL", ctx.getAuditAction());
        assertEquals("TestPolicy", ctx.getRuleSource());
        assertEquals(Integer.valueOf(100), ctx.getTimeout());

        ctx.recycle();
    }
}
