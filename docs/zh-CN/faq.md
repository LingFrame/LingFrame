# 常见问题 (FAQ)

本文档收集了关于灵珑的常见问题和解答。

---

## 一、基础概念

### Q1: 灵珑是什么？

**A:** 灵珑是一个面向 JVM 单进程长期运行系统的秩序体系。它专注于解决单体系统在长期运行过程中逐渐失控的问题，而不是简单地"拆分微服务"。

### Q2: 灵珑和 OSGi 有什么区别？

**A:**

| 对比项 | 灵珑 | OSGi |
|--------|------|------|
| 定位 | 运行时治理框架 | 模块化系统 |
| 学习曲线 | 较低 | 较高 |
| Spring 集成 | 原生支持 | 需要额外配置 |
| 治理能力 | 内置熔断/限流/权限 | 需要额外实现 |
| 热更新 | 支持 | 支持 |

### Q3: 灵珑和 Spring Boot 的关系是什么？

**A:** 灵珑不是 Spring Boot 的替代品。治理内核（`lingframe-core`）不以 Spring 为前提；Spring Boot 集成在 `lingframe-runtime`：公共 `lingframe-spring-boot-starter` + 栈专属 `lingframe-spring-boot2-starter` / `lingframe-spring-boot3-starter`（类型化 `javax` / `jakarta`，禁止反射探测 Servlet）。Dashboard 保持**单 GAV** + 矩阵源码集。细则见 [开发手册](../../DEVELOPMENT_MANUAL.md) 第 5.2 节。

### Q4: "灵元"是什么？

**A:** 灵元（Ling）是灵珑中的核心概念，指在灵核进程内被独立加载和管理的业务单元。不要把它降格成“插件”——灵元是受治理、可规范卸载的运行单元。

### Q5: 灵珑适合什么场景？

**A:**

✅ 适合：
- 已运行多年、不能轻易停机或重写的单体系统
- 代码膨胀为“分布式单体”、需要内部拆解解耦的大型微服务
- 希望逐步引入隔离、灰度、限流、熔断、权限审计的团队
- 想在不推翻现有系统的前提下建立运行时秩序

❌ 不适合：
- 把它当作微服务的替代品（两者是互补关系而非替代关系）
- 纯前端插件市场或低代码平台
- 期望自动消除业务复杂性

### Q6: 灵珑和微服务 / Service Mesh 是什么关系？

**A:** 不是替代关系，而是互补关系。

- **微服务 / Service Mesh 解决的是进程之间**的拆分、部署、网络路由与通信；
- **灵珑解决的是单个 JVM 进程内部**的边界建立、运行时治理与渐进演进。

灵珑不回答“系统应该拆成几个服务”，而是回答“每个服务内部应该如何持续演进”。一个微服务如果随着业务发展代码日益膨胀（变成分布式单体），同样可以在该微服务进程内部引入灵珑，将新旧能力划分为灵元进行渐进式改造，而不需要再次进行一次高风险的大规模跨网络重构。

---

## 二、架构与设计

### Q7: 为什么采用双层状态机设计？

**A:** 双层状态机（InstanceStatus + RuntimeStatus）的设计目标是：

1. **状态所有权明确**：实例状态和运行时状态由不同的协调器管理
2. **事件驱动联动**：两层状态通过事件联动，而不是对象互相写状态
3. **可观测性**：状态变更都有事件发布，便于追踪

详见 [架构设计](architecture.md) §1。

### Q8: Shared API 为什么不能热更新？

**A:** Shared API 是进程级公共契约边界。如果允许热更新，会导致：

1. 不同灵元看到不同版本的契约
2. 同一个类被不同 ClassLoader 加载，导致 ClassCastException
3. 类型系统整体失真

因此，Shared API 在灵元加载前预加载并冻结，变更需要重启进程。

详见 [Shared API 指南](shared-api-guidelines.md)。

### Q9: Pipeline 为什么要分这么多阶段？

**A:** Pipeline 阶段划分是为了：

1. **职责分离**：每个 Filter 只做一件事
2. **依赖明确**：启动时校验阶段顺序，避免运行时问题
3. **可扩展**：可以在任意阶段插入自定义 Filter

### Q10: Child-First ClassLoader 有什么风险？

**A:** Child-First 意味着灵元优先加载自己的类，可能导致：

1. **版本冲突**：灵元使用的库版本与灵核不同
2. **类型不兼容**：同一个类被不同 ClassLoader 加载

解决方案：
- 使用 Shared API 共享公共类
- 在 `ling.yml` 中声明依赖

---

## 三、使用问题

### Q11: 如何调试灵元代码？

**A:** 几种方式：

1. **远程调试**：启动时添加 `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005`
2. **开发模式**：设置 `dev-mode: true`，启用热更新
3. **日志调试**：调整日志级别到 DEBUG 或 TRACE

### Q12: 灵元之间如何通信？

**A:** 灵元之间通过服务接口通信：

```java
// 使用 @LingReference 注入其他灵元的服务
// 框架会在所有已安装的灵元中查找实现了该接口的 Bean
@LingReference
private UserService userService;
```

### Q13: 如何实现灰度发布？

**A:** 通过 Dashboard 的契约权重路由 API 配置——同一契约下多个 provider 按权重比例分流，二元只是 N=2 的特例：

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/contract-routing/order-ling/weight \
  -H "Content-Type: application/json" \
  -d '{"providerKey": "order-ling", "weight": 20}'
```

> `providerKey` 即路由键——迁移期裸 `lingId`，迭代期 `lingId:version`，与路由读路径键化一致。`weight` 为 0-100 整数；Dashboard 下发后立即覆盖 `ProviderWeightRouter` 内的运行期权重，IPC 与 Web 治理链同时生效。详见 [Dashboard 文档](dashboard.md)。

### Q14: 如何处理灵元依赖？

**A:** 在 `ling.yml` 中声明依赖：

```yaml
dependencies:
  - user-ling
  - common-ling
```

灵珑会确保依赖的灵元先加载。

### Q15: 灵元可以使用哪些 Spring 特性？

**A:** 灵元可以使用大部分 Spring 特性：

- ✅ @Component / @Service / @Repository
- ✅ @Autowired 依赖注入
- ✅ @Value 配置注入
- ✅ @Transactional 事务
- ⚠️ @Configuration 需要注意 ClassLoader 隔离
- ✅ @SpringBootApplication 支持（Spring Boot 灵元的标准入口方式）

---

## 四、故障排查

### Q16: 灵元加载失败怎么办？

**A:** 检查以下方面：

1. **日志**：查看日志中的错误信息
2. **类路径**：确保灵元 JAR 包含所有必要类
3. **依赖**：确保依赖的灵元已加载
4. **权限**：确保灵元有必要的 capabilities

详细排查步骤见 [故障排查手册](troubleshooting.md)。

### Q17: 内存持续增长怎么办？

**A:** 可能原因：

1. **ClassLoader 泄漏**：检查灵元是否有静态集合未清理
2. **ThreadLocal 泄漏**：确保灵元停止时清理 ThreadLocal
3. **监听器泄漏**：使用 EventBus 而不是手动注册监听器

泄漏检测是运行时内建能力，无需额外配置开关。开发模式下（`dev-mode: true`）自动启用激进诊断（`DEV_AGGRESSIVE` / `DEV_BOUNDED`），生产模式下自动降级为被动观测（`PROD_PASSIVE`）：
```yaml
lingframe:
  dev-mode: true  # 开发模式自动启用激进泄漏诊断
```

### Q18: 熔断器一直打开怎么办？

**A:**

1. 检查下游服务是否正常
2. 调整熔断阈值（在 governance 配置中）
3. 通过 Dashboard 查看熔断器状态

### Q19: 如何查看灵元状态？

**A:** 通过 Dashboard API：

```bash
# 查看所有灵元
curl http://localhost:8888/lingframe/dashboard/lings

# 查看单个灵元
curl http://localhost:8888/lingframe/dashboard/lings/{lingId}
```

---

## 五、Dashboard 相关

### Q20: Dashboard 如何启用？

**A:**

```yaml
lingframe:
  dashboard:
    enabled: true
```

添加依赖：
```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-dashboard</artifactId>
</dependency>
```

### Q21: Dashboard 安装接口为什么不能用？

**A:** 安装接口默认关闭，需要显式开启：

```yaml
lingframe:
  dashboard:
    install-enabled: true
```

### Q22: 热更新接口为什么返回 403？

**A:** 热更新接口只在开发模式可用：

```yaml
lingframe:
  dev-mode: true
```

---

## 六、其他问题

### Q23: 灵珑支持哪些 JDK 版本？

**A:** 本仓库构建 / CI 矩阵：

| 路径 | JDK | 说明 |
| --- | --- | --- |
| **主路径 / 示例默认** | **JDK 8** | 默认 Maven profile + Spring Boot 2.7 |
| **支持线** | **JDK 17** | 显式 `-Pspring-boot3`（Spring Boot 3.x） |

应用侧其它 LTS JDK 可能可跑，但公开验证以以上两条矩阵为准。切换矩阵务必 `clean`（SB3 的 class 在 JDK 8 上会失败）。

### Q24: 灵珑支持哪些 Spring Boot 版本？

**A:**

| 路径 | Spring Boot | Starter 坐标 |
| --- | --- | --- |
| **主路径** | **2.7.x** | `lingframe-spring-boot2-starter`（+ 公共 `lingframe-spring-boot-starter`） |
| **支持线** | **3.x**（仓库 BOM 跟踪当前 3.5.x 线） | `lingframe-spring-boot3-starter`（+ 公共 starter） |

禁止在公共代码写 Servlet 类型或再拆 Dashboard 双坐标。见 [生产配置清单](production-hardening.md) 第 6 节与开发手册第 5.2 节。

### Q25: 如何参与灵珑开发？

**A:**

1. Fork 仓库
2. 阅读贡献指南
3. 提交 Pull Request

### Q26: 在哪里可以获取帮助？

**A:**

- **文档**：本目录下的文档
- **Issues**：提交问题反馈

### Q27: 灵珑的开源协议是什么？

**A:** Apache License 2.0，可以免费用于商业项目。

---

## 七、路线图相关

### Q28: Prometheus/Grafana 集成什么时候支持？

**A:** 当前已经支持 Micrometer 指标桥接。若灵核应用提供 `MeterRegistry`，LingFrame 会自动注册灵元健康指标与治理信号指标；若灵核再引入 `micrometer-registry-prometheus` 并暴露 `/actuator/prometheus`，即可直接被 Prometheus 抓取。示例可参考 `lingframe-example-lingcore-app`。

### Q29: 消息代理（Kafka/RabbitMQ）什么时候支持？

**A:** 在 Phase 5 生态完善阶段规划，详见 [路线图](roadmap.md)。

---

## 八、术语表——一眼看懂

这份术语表为第一次接触灵珑的人准备。

如果其他文档看起来太密，先读完这一页，再回去会轻松很多。

### LingFrame

项目整体名称：一个面向长期运行系统的 JVM 运行时治理框架。

### 灵核

`LingCore`，承载治理能力的核心应用侧进程。

### 灵元

`Ling`，也就是在灵核进程里被独立加载、隔离和治理的业务单元。

### Shared API

灵核与灵元之间、或灵元之间的进程级公共契约边界。

它当前用于承载：

- 接口
- DTO
- 必要注解
- 必要常量

### 治理内核

负责统一应用治理规则的运行时核心层。

### 调用 Pipeline

处理调用治理决策的有序主链。

### `NORMAL`

执行治理，同时执行真实终端调用。

### `SIMULATION`

执行真实治理路径，但不产生真实业务副作用。

### `GOVERN_ONLY`

执行治理，但在 Pipeline 内不做终端调用。

### `InstanceStatus`

某一个灵元实例的生命周期状态。

### `RuntimeStatus`

灵核视角下，一个灵元运行时的宏观可用性状态。

### Dashboard

当前 Dashboard 更应该被理解为治理控制面，而不只是一个页面。

### 灰度

把一部分流量路由到指定灵元版本或实例，而不是全部走默认路径。

### 卸载清理

灵元被移除时，运行时会排空请求、驱逐资源、清理 classloader 相关状态，并执行泄漏诊断。
