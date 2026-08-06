package com.lingframe.example.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * SaaS 商城灵核启动类。
 * <p>
 * 灵核定位：依赖 ling-mall 作为底座代码库（DataSource、Mapper、Service、Controller 全部复用），
 * 自身不编写任何业务代码——这是「绞杀迁移」的核心约束：core 不可热加载，故任何迁出新功能接口
 * 都不允许在 core 写适配层，必须由灵元 implements 灵核原生接口实现切流。
 * <p>
 * scanBasePackages 只扫 ling-mall 的包：
 * <ul>
 *   <li>灵核所有 Controller/Service/Mapper 由 ling-mall 提供，扫描 com.lingframe.example.mall 即可装载；</li>
 *   <li>不扫 com.lingframe.example.saas 宽前缀——避免误扫 saas-ling-oauth/seckill/refund 三个灵元的启动类，
 *       其 @SpringBootApplication(exclude=...) 会反向污染灵核自动配置，导致灵核 DataSourceAutoConfiguration 被排除。</li>
 * </ul>
 * 灵元 HTTP 入口由灵元自暴露 @RestController，经 WebInterfaceManager 注册到灵核 Spring MVC，
 * 灵核 ClassLoader 不接触灵元类，保证灵元可热卸载。
 */
@EnableCaching
@SpringBootApplication(scanBasePackages = {
        "com.lingframe.example.mall"
})
public class SaasMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(SaasMallApplication.class, args);
    }
}
