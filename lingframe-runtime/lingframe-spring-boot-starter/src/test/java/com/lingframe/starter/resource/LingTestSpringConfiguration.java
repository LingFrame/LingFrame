package com.lingframe.starter.resource;

import com.lingframe.starter.configuration.LingFrameCoreConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

// proxyBeanMethods=false：本配置类没有任何 @Bean 方法，不需要 CGLIB 代理语义。
// 关键：在 surefire 单 fork 复用 AppClassLoader 下，若此类被 CGLIB 增强，同 JVM 内
// 第二次重建 ApplicationContext（如 @DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)）
// 会再次尝试 defineClass 同名增强类，触发
// LinkageError: attempted duplicate class definition。
// 关闭代理即跳过 CGLIB 增强，从根本上消除该冲突（reuseForks 可回到默认 true）。
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = HibernateJpaAutoConfiguration.class)
@Import(LingFrameCoreConfiguration.class)
public class LingTestSpringConfiguration {
}
