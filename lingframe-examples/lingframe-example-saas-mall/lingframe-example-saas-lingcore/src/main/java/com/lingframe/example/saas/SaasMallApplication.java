package com.lingframe.example.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
// scanBasePackages 只显式追加单体 mall 的复用包；
// 灵核自己的 controller/service 由 @SpringBootApplication 默认从启动类所在包 com.lingframe.example.saas 扫描覆盖。
// 不扫 com.lingframe.example.saas 宽前缀——避免误扫 saas-ling-oauth/seckill/refund 三个灵元的启动类，
// 其 @SpringBootApplication(exclude=...) 会反向污染灵核自动配置，导致灵核 DataSourceAutoConfiguration 被排除。
@SpringBootApplication(scanBasePackages = {
        "com.lingframe.example.mall",
        "com.lingframe.example.saas.controller",
        "com.lingframe.example.saas.service"
})
public class SaasMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(SaasMallApplication.class, args);
    }
}
