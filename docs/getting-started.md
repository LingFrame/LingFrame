# 快速入门

本文档帮助你快速了解和使用 LingFrame。

## 环境准备

- JDK 21+
- Maven 3.8+

## 构建框架

```bash
git clone https://github.com/lingframe/lingframe.git
cd lingframe
mvn clean install -DskipTests
```

## 运行示例

```bash
cd lingframe-samples/lingframe-sample-host-app
mvn spring-boot:run
```

启动后，DevLoader 会自动加载示例插件 `lingframe-sample-plugin-user`。

## 核心概念

### 三层架构

```
┌─────────────────────────────────────────┐
│           Core（仲裁核心）               │
│   生命周期管理 · 权限治理 · 上下文隔离    │
└─────────────────┬───────────────────────┘
                  ▼
┌─────────────────────────────────────────┐
│     Infrastructure Plugins（基础设施）   │
│          存储 · 缓存 · 消息              │
└─────────────────┬───────────────────────┘
                  ▼
┌─────────────────────────────────────────┐
│       Business Plugins（业务插件）       │
│      用户中心 · 订单服务 · 支付模块       │
└─────────────────────────────────────────┘
```

### 关键原则

1. **零信任**：业务插件只能通过 Core 访问基础设施
2. **上下文隔离**：每个插件独立的 Spring 子上下文
3. **FQSID 路由**：服务通过 `pluginId:serviceId` 全局唯一标识

## 创建宿主应用

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-spring-boot3-starter</artifactId>
    <version>${lingframe.version}</version>
</dependency>
```

### 2. 启动类

```java
@SpringBootApplication
public class HostApplication {
    public static void main(String[] args) {
        // 开发模式：权限不足时仅警告
        LingFrameConfig.setDevMode(true);
        SpringApplication.run(HostApplication.class, args);
    }
}
```

### 3. 加载插件

```java
@Component
@RequiredArgsConstructor
public class PluginLoader implements CommandLineRunner {

    private final PluginManager pluginManager;

    @Override
    public void run(String... args) {
        // 生产模式：加载 JAR
        pluginManager.install("my-plugin", "1.0.0", new File("plugins/my-plugin.jar"));

        // 开发模式：加载编译目录
        pluginManager.installDev("my-plugin", "dev", new File("target/classes"));
    }
}
```

## 创建插件

详见 [插件开发指南](plugin-development.md)

## 下一步

- [插件开发指南](plugin-development.md) - 学习如何开发插件
- [架构设计](architecture.md) - 深入了解框架架构
- [API 参考](api-reference.md) - 查看完整 API 文档
