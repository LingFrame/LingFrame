package com.lingframe.dashboard.structure;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Dashboard 架构守护测试。
 * <p>
 * 守住 Phase B 边界收敛契约：dashboard 业务类不得直接依赖 core 内部实现，
 * 只能通过已公开的治理内核 API（{@code GovernanceAdminService}、{@code InvocationContextBuilder}、
 * {@code RuntimeCoordinator}、{@code LingFrameInfo} 等）与内核交互。
 * <p>
 * 装配类（{@code *Configuration}）是胶水层，不约束；Web/AOP 适配层的适配语义不约束。
 */
@AnalyzeClasses(packages = "com.lingframe.dashboard", importOptions = ImportOption.DoNotIncludeTests.class)
public class DashboardBoundaryArchUnitTest {

    /**
     * 禁止直接持有 {@code LocalGovernanceRegistry}：治理补丁读写必须经 {@code GovernanceAdminService} 委托。
     * <p>
     * 装配类（{@code *Configuration}）是胶水层不约束；用 {@code haveSimpleNameNotContaining}
     * 排除装配类，兼容 ArchUnit 0.23.x API。
     */
    @ArchTest
    static final ArchRule noLocalGovernanceRegistryDependency =
            noClasses().that().resideInAPackage("..dashboard..")
                    .and().haveSimpleNameNotContaining("Configuration")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.lingframe.core.governance.LocalGovernanceRegistry");

    /**
     * 禁止直接操作 {@code StateMachine}：状态机只读视图必须经 {@code RuntimeCoordinator} 委托。
     */
    @ArchTest
    static final ArchRule noStateMachineDependency =
            noClasses().that().resideInAPackage("..dashboard..")
                    .and().haveSimpleNameNotContaining("Configuration")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.lingframe.core.fsm.StateMachine");

    /**
     * 禁止静态穿透 {@code LingFrameConfig.current()}：配置只读必须经注入的 {@code LingFrameInfo} 接口。
     * <p>
     * 静态穿透绕过了 starter 的 bean 装配，使 dashboard 与 core 的运行时配置脱钩。
     */
    @ArchTest
    static final ArchRule noLingFrameConfigCurrentCall =
            noClasses().that().resideInAPackage("..dashboard..")
                    .and().haveSimpleNameNotContaining("Configuration")
                    .should().callMethod("com.lingframe.core.config.LingFrameConfig", "current");
}
