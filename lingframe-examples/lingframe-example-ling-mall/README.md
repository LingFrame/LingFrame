# lingframe-example-ling-mall

> 老单体最小化改造为灵元的范例。

本模块展示一个**完全不依赖灵珑框架**的标准 Spring Boot 商城单体应用，如何通过最小化改造变成灵元——零业务代码改动、零灵珑依赖，仅添加灵元元数据即可获得灵元基础设施（类加载隔离、独立生命周期、可热加载/卸载）。

## 1. 老单体长什么样

这是一个典型的 Spring Boot 商城应用：

- `MallApplication`：标准 `@SpringBootApplication` 启动类
- `service/` + `service/impl/`：订单、商品、库存、优惠券、退款、秒杀等业务实现
- `mapper/`：MyBatis-Plus 数据访问层
- `entity/`：JPA 实体
- `controller/`：Spring MVC REST 接口
- `security/`：JWT 认证 + Spring Security

pom.xml 只有 Spring Boot + MyBatis-Plus + JWT + H2，**没有任何 `lingframe-*` 依赖**。业务代码中**零 `@LingService`、零 `@LingReference`**。

## 2. 最小化改造做了什么

只加了两样东西，**不改任何业务代码**：

### 2.1 添加 `ling.yml` 灵元元数据

[`src/main/resources/ling.yml`](src/main/resources/ling.yml)：

```yaml
id: mall-ling
version: 1.0.0
provider: "LingFrame"
description: "商城单体灵元"
mainClass: "com.lingframe.example.mall.MallApplication"

governance:
    permissions: []
    capabilities:
      - capability: "storage:sql"
        accessType: "WRITE"
      - capability: "cache:local"
        accessType: "WRITE"
```

灵核装载器据此识别灵元 ID、版本、入口类，创建 `LingClassLoader` 隔离加载。

> ⚠️ **非治理演示意图**：`ling.yml` 声明的 `storage:sql WRITE` / `cache:local WRITE` 能力属于**声明式占位**，代码中零 `@RequiresPermission`、零 `@LingService`，能力声明没有代码级约束点锚定。本模块定位是「老单体最小化改造灵元」示范，刻意保持零灵珑依赖、零业务代码改动，仅演示类加载隔离与独立生命周期。**不要把它当作带治理约束的灵元范本**；需要治理约束请参考 `lingframe-example-saas-mall` 的 `@RequiresPermission`/`@LingService` 路径。

### 2.2 添加 `Ling-Id` JAR manifest

[`pom.xml`](pom.xml) 的 `maven-jar-plugin` 配置：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-jar-plugin</artifactId>
    <configuration>
        <archive>
            <manifestEntries>
                <Ling-Id>mall-ling</Ling-Id>
                <Ling-Version>${project.version}</Ling-Version>
            </manifestEntries>
        </archive>
    </configuration>
</plugin>
```

打包后的 JAR 自带灵元标识，便于灵核在部署目录中识别。

## 3. 改造后获得了什么

| 能力 | 改造前（单体） | 改造后（灵元） |
| --- | --- | --- |
| 类加载隔离 | ❌ 与其他模块共享 ClassLoader | ✅ 独立 `LingClassLoader`，Child-First 策略 |
| 独立生命周期 | ❌ 随进程生死 | ✅ 可热加载/热卸载，独立 START/STOP |
| 治理埋点 | ❌ 无 | ✅ 流量指标、审计日志、权限校验 |
| Dashboard 管理 | ❌ 无 | ✅ 可视化生命周期管理 |
| 跨灵元协作 | ❌ 无 | ⚠️ 需进一步加 `@LingService` 暴露契约（见下） |

## 4. 后续如何渐进暴露契约

最小化改造后，灵元已获得基础设施能力，但**不对外暴露任何契约**——灵核和其他灵元无法通过 `@LingReference` 调用它的服务。

要暴露契约，需要进一步改造（参考 [`lingframe-example-saas-mall`](../lingframe-example-saas-mall) 的迁移路径）：

1. 添加 `lingframe-api` 依赖
2. 在需要暴露的方法上加 `@LingService` 注解（或依赖隐式接口注册）
3. 灵核的 `SpringLingContainer.scanAndRegisterLingServices` 会自动扫描注册为 weight=0 的灵元 provider

这一步是**可选的渐进式演进**——最小化改造的灵元即使不暴露契约，也已获得类加载隔离和独立生命周期价值。

## 5. 单独运行

本模块本身仍是完整的 Spring Boot 应用，可独立运行：

- 端口：8082
- H2 内存数据库，启动自动执行 `schema.sql` + `data.sql`
- Swagger UI：`http://localhost:8082/swagger-ui.html`
- H2 控制台：`http://localhost:8082/h2-console`

运行 [`MallApplication`](src/main/java/com/lingframe/example/mall/MallApplication.java) 即可。

## 6. 作为灵元加载

将本模块 `mvn package` 后的 JAR（或 target 目录路径）加入灵核的 `ling-roots` 配置：

```yaml
lingframe:
  enabled: true
  ling-roots:
    - <路径>/lingframe-example-ling-mall
```

灵核启动时会通过 `LingClassLoader` 加载本模块，按 `ling.yml` 的 `mainClass` 创建灵元 Spring 子容器，`MallApplication` 作为灵元入口启动。

## 7. 在 saas-mall 中的另一种角色

本模块在 [`lingframe-example-saas-mall`](../lingframe-example-saas-mall) 中还有另一种复用方式：作为 `lingframe-example-saas-lingcore` 的 Maven 类路径依赖引入，为灵核提供底座 entity/mapper/service。

这是「绞杀迁移」的另一种策略——不把老单体作为独立灵元加载，而是作为灵核类路径的一部分复用，再逐步把业务能力抽成独立灵元。详见 saas-mall 的迁移路径文档。
