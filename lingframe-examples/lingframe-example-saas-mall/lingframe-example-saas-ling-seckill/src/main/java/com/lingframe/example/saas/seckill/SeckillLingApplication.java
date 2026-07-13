package com.lingframe.example.saas.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;

/**
 * SaaS 商城秒杀削峰灵元启动类。
 * <p>
 * 灵元 mainClass 必须是 {@code @SpringBootApplication} 类，灵珑装载器据此创建灵元 Spring 子容器。
 * <p>
 * 灵元不持有独立数据源——通过 {@code @LingReference} 跨灵元契约引用灵核底座的数据能力，
 * 故排除数据源与 SQL 初始化自动配置，避免子容器重复继承灵核的 schema/data 脚本触发主键冲突。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class
})
public class SeckillLingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillLingApplication.class, args);
    }
}
