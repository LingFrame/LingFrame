package com.lingframe.core.structure;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.concurrent.locks.ReentrantLock;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * LingFrame Core 核心架构守卫测试。
 * <p>
 * 固化微内核核心架构红线：
 * 1. SPI 纯洁性：SPI 接口定义不得逆向依赖具体实现；
 * 2. 生命周期锁唯一性：除 DefaultLingLifecycleEngine 外，不得私自持有 ReentrantLock；
 * 3. 架构分层：com.lingframe.core 内部实现不得被 API 层反向依赖。
 */
@AnalyzeClasses(packages = "com.lingframe.core", importOptions = ImportOption.DoNotIncludeTests.class)
public class LingFrameCoreArchUnitTest {

    /**
     * SPI 纯洁性规则：com.lingframe.core.spi 包下的接口/类型不得依赖 Default 开头的具体实现类。
     */
    @ArchTest
    static final ArchRule spiShouldNotDependOnDefaultImplementations =
            noClasses().that().resideInAPackage("..core.spi..")
                    .should().dependOnClassesThat()
                    .haveSimpleNameStartingWith("Default");

    /**
     * 生命周期分段锁集中化规则：除 DefaultLingLifecycleEngine / LockWrapper 以及 LingInstance 实例独占锁外，
     * core 模块中不得有其他类随意声明 ReentrantLock 字段（确保生命周期锁机制收归内核引擎）。
     */
    @ArchTest
    static final ArchRule lifecycleLocksShouldBeCentralizedInLifecycleEngine =
            fields().that().haveRawType(ReentrantLock.class)
                    .should().beDeclaredInClassesThat()
                    .haveSimpleName("LockWrapper")
                    .orShould().beDeclaredInClassesThat()
                    .haveSimpleName("DefaultLingLifecycleEngine")
                    .orShould().beDeclaredInClassesThat()
                    .haveSimpleName("LingInstance");
}
