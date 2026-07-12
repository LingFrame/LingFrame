package com.lingframe.starter.structure;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Runtime starter 架构守护测试。
 * <p>
 * 守住 Phase C 边界收敛契约：runtime 业务类不得直接持有 {@code LocalGovernanceRegistry}
 * 或调 {@code LingFrameConfig.current()} 静态穿透——治理读写必须经 {@link GovernanceAdminService} 委托，
 * 配置只读必须经注入的 {@code LingFrameInfo} 接口。
 * <p>
 * 装配类（{@code *Configuration}）是胶水层，不约束；Web/AOP 适配层的适配语义不约束。
 */
@AnalyzeClasses(packages = "com.lingframe.starter", importOptions = ImportOption.DoNotIncludeTests.class)
public class RuntimeStarterBoundaryArchUnitTest {

    /**
     * 禁止 runtime 业务类直接持有 {@code LocalGovernanceRegistry}：
     * 治理策略读写必须经 {@code GovernanceAdminService} 委托。
     * <p>
     * 装配类（{@code *Configuration}）继续装配 {@code LocalGovernanceRegistry} bean 供下游使用是本职，豁免。
     */
    @ArchTest
    static final ArchRule noLocalGovernanceRegistryDependency =
            noClasses().that().resideInAPackage("..starter..")
                    .and().haveSimpleNameNotContaining("Configuration")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.lingframe.core.governance.LocalGovernanceRegistry");

    /**
     * 禁止静态穿透 {@code LingFrameConfig.current()}：
     * 配置只读必须经注入的 {@code LingFrameInfo} 接口，写必须经注入的 {@code LingFrameConfig} bean。
     * <p>
     * 静态穿透绕过了 starter 的 bean 装配，使 runtime 与 core 的运行时配置脱钩。
     * 装配类豁免——装配阶段可能需要静态上下文校验。
     */
    @ArchTest
    static final ArchRule noLingFrameConfigCurrentCall =
            noClasses().that().resideInAPackage("..starter..")
                    .and().haveSimpleNameNotContaining("Configuration")
                    .should().callMethod("com.lingframe.core.config.LingFrameConfig", "current");
}
