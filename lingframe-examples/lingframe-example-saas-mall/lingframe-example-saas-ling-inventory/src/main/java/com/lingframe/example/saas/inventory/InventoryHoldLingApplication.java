package com.lingframe.example.saas.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;

/**
 * 带 TTL 的库存预占灵元启动类。
 * <p>
 * 灵元 mainClass 必须是 {@code @SpringBootApplication} 类，灵珑装载器据此创建灵元 Spring 子容器。
 * <p>
 * 灵元不持有独立数据源——预占记录存内存，库存扣减/释放通过 {@code @LingReference} delegate 灵核
 * {@code InventoryService} 执行，故排除数据源与 SQL 初始化自动配置。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class
})
public class InventoryHoldLingApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryHoldLingApplication.class, args);
    }
}
