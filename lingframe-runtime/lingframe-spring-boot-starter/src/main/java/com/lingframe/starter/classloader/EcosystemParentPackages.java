package com.lingframe.starter.classloader;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 生态环境强制父委派包常量。
 * <p>
 * 边界归属：本类属 runtime 适配层（starter），持「宿主生态环境必须委派给父加载器」的包前缀。
 * core 的 {@code LingClassLoader.FORCE_PARENT_PACKAGES} 只持灵珑自身依赖（JDK / 契约 / slf4j / lombok / snakeyaml），
 * 生态环境包不散入 core——避免 core 替宿主决策「该共享什么」。
 * <p>
 * 收敛到此类的目的：starter 与 native 两个适配路径共用同一份生态环境清单，
 * 避免散在两处各自维护导致漂移。宿主若需追加，经 {@code LingFrameConfig} 显式配置后由适配层注入。
 * <p>
 * 当前清单：Spring / Jackson / Logback / Log4j2。snakeyaml 不在此——它是灵珑自身用解析 ling.yml 的门面，留 core。
 */
public final class EcosystemParentPackages {

    private EcosystemParentPackages() {
    }

    private static final Set<String> ECOSYSTEM_PACKAGES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "org.springframework.",   // Spring 框架
                    "com.fasterxml.jackson.", // Jackson JSON 处理
                    "ch.qos.logback.",        // Logback 日志实现
                    "org.apache.logging.log4j." // Log4j2 日志实现
            )));

    /**
     * 返回生态环境强制父委派包前缀的不可变视图。
     *
     * @return 包前缀集合
     */
    public static Collection<String> ecosystemDefaults() {
        return ECOSYSTEM_PACKAGES;
    }
}
