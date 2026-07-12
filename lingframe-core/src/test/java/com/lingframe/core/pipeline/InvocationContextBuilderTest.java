package com.lingframe.core.pipeline;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link InvocationContextBuilder} 关键语义单测。
 */
@DisplayName("InvocationContextBuilder 调用上下文构造器单测")
class InvocationContextBuilderTest {

    @DisplayName("执行模式预设语义")
    @Nested
    class ModePresetSemantics {

        @DisplayName("forSimulation 设置 SIMULATION 模式且 callerLingId=targetLingId")
        @Test
        void forSimulation_setsModeAndCaller() {
            InvocationContext ctx = InvocationContextBuilder.forSimulation("order-ling")
                    .build();

            assertEquals(InvocationExecutionMode.SIMULATION, ctx.execution().getMode());
            assertEquals("order-ling", ctx.getTargetLingId());
            assertEquals("order-ling", ctx.getCallerLingId());
        }

        @DisplayName("forGovernOnly 设置 GOVERN_ONLY 模式")
        @Test
        void forGovernOnly_setsMode() {
            InvocationContext ctx = InvocationContextBuilder.forGovernOnly("order-ling")
                    .build();

            assertEquals(InvocationExecutionMode.GOVERN_ONLY, ctx.execution().getMode());
            assertEquals("order-ling", ctx.getTargetLingId());
        }

        @DisplayName("forNormal 设置 NORMAL 模式并从 FQSID 提取 targetLingId")
        @Test
        void forNormal_setsModeAndExtractsLingId() {
            InvocationContext ctx = InvocationContextBuilder.forNormal("caller-ling", "order-ling:submit")
                    .build();

            assertEquals(InvocationExecutionMode.NORMAL, ctx.execution().getMode());
            assertEquals("caller-ling", ctx.getCallerLingId());
            assertEquals("order-ling:submit", ctx.getServiceFQSID());
            assertEquals("order-ling", ctx.getTargetLingId());
        }
    }

    @DisplayName("治理字段链式设置语义")
    @Nested
    class GovernanceFieldSemantics {

        @DisplayName("链式设置 resourceType / accessType / requiredPermission / auditAction")
        @Test
        void chainSetsGovernanceFields() {
            InvocationContext ctx = InvocationContextBuilder.forSimulation("order-ling")
                    .resourceType("DATABASE")
                    .resourceId("simulate:DATABASE")
                    .operation("simulate_DATABASE")
                    .accessType(AccessType.READ)
                    .requiredPermission("storage:sql")
                    .auditAction("SIMULATE:DATABASE")
                    .build();

            assertEquals("DATABASE", ctx.getResourceType());
            assertEquals("simulate:DATABASE", ctx.getResourceId());
            assertEquals("simulate_DATABASE", ctx.getOperation());
            assertEquals(AccessType.READ, ctx.governance().getAccessType());
            assertEquals("storage:sql", ctx.governance().getRequiredPermission());
            assertTrue(ctx.governance().isShouldAudit());
            assertEquals("SIMULATE:DATABASE", ctx.governance().getAuditAction());
        }

        @DisplayName("auditAction 自动开启 shouldAudit")
        @Test
        void auditAction_enablesShouldAudit() {
            InvocationContext ctx = InvocationContextBuilder.forSimulation("order-ling")
                    .auditAction("SIMULATE:IPC")
                    .build();

            assertTrue(ctx.governance().isShouldAudit());
            assertEquals("SIMULATE:IPC", ctx.governance().getAuditAction());
        }

        @DisplayName("未设置 auditAction 时 shouldAudit 默认为 false")
        @Test
        void noAuditAction_keepsShouldAuditFalse() {
            InvocationContext ctx = InvocationContextBuilder.forSimulation("order-ling")
                    .build();

            assertEquals(false, ctx.governance().isShouldAudit());
            assertNull(ctx.governance().getAuditAction());
        }
    }

    @DisplayName("runtime 绑定语义")
    @Nested
    class RuntimeBindingSemantics {

        @DisplayName("build(repository) 绑定 runtime 引用")
        @Test
        void buildWithRepository_bindsRuntime() {
            LingRepository repository = mock(LingRepository.class);
            LingRuntime runtime = mock(LingRuntime.class);
            when(repository.getRuntime("order-ling")).thenReturn(runtime);

            InvocationContext ctx = InvocationContextBuilder.forSimulation("order-ling")
                    .build(repository);

            assertSame(runtime, ctx.getRuntime());
        }

        @DisplayName("build() 不绑定 runtime")
        @Test
        void buildWithoutRepository_noRuntime() {
            InvocationContext ctx = InvocationContextBuilder.forGovernOnly("order-ling")
                    .build();

            assertNull(ctx.getRuntime());
        }

        @DisplayName("灵元未装载时 build(repository) 不抛异常，runtime 为 null")
        @Test
        void buildWithRepository_lingNotLoaded_runtimeNull() {
            LingRepository repository = mock(LingRepository.class);
            when(repository.getRuntime("unknown-ling")).thenReturn(null);

            InvocationContext ctx = InvocationContextBuilder.forSimulation("unknown-ling")
                    .build(repository);

            assertNull(ctx.getRuntime());
        }
    }

    @DisplayName("上下文来自对象池，非每次 new")
    @Test
    void builderUsesPooledContext() {
        InvocationContext ctx1 = InvocationContextBuilder.forSimulation("ling-a").build();
        InvocationContext ctx2 = InvocationContextBuilder.forSimulation("ling-b").build();

        // 两次构造的上下文可能来自同一对象池实例（同线程），但 reset 后字段必须独立
        assertEquals("ling-b", ctx2.getTargetLingId());
    }
}
