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

**A:** 灵珑基于 Spring Boot 构建，提供了 Spring Boot Starter 用于快速集成。它不是 Spring Boot 的替代品，而是在 Spring Boot 之上提供运行时治理能力。

### Q4: "灵元"是什么？

**A:** 灵元（Ling）是灵珑中的核心概念，指在宿主应用（灵核）进程内被独立加载和管理的业务单元。可以理解为"受治理的插件"。

### Q5: 灵珑适合什么场景？

**A:**

✅ 适合：
- 已运行多年、不能轻易停机或重写的单体系统
- 希望逐步引入隔离、灰度、限流、熔断、权限审计的团队
- 想在不推翻现有系统的前提下建立运行时秩序

❌ 不适合：
- 当作微服务替代品
- 纯前端插件市场或低代码平台
- 期望自动消除业务复杂性

---

## 二、架构与设计

### Q6: 为什么采用双层状态机设计？

**A:** 双层状态机（InstanceStatus + RuntimeStatus）的设计目标是：

1. **状态所有权明确**：实例状态和运行时状态由不同的协调器管理
2. **事件驱动联动**：两层状态通过事件联动，而不是对象互相写状态
3. **可观测性**：状态变更都有事件发布，便于追踪

详见 [双层状态机架构](runtime-dual-state-machine-architecture.md)。

### Q7: Shared API 为什么不能热更新？

**A:** Shared API 是进程级公共契约边界。如果允许热更新，会导致：

1. 不同灵元看到不同版本的契约
2. 同一个类被不同 ClassLoader 加载，导致 ClassCastException
3. 类型系统整体失真

因此，Shared API 在灵元加载前预加载并冻结，变更需要重启进程。

详见 [Shared API 指南](shared-api-guidelines.md)。

### Q8: Pipeline 为什么要分这么多阶段？

**A:** Pipeline 阶段划分是为了：

1. **职责分离**：每个 Filter 只做一件事
2. **依赖明确**：启动时校验阶段顺序，避免运行时问题
3. **可扩展**：可以在任意阶段插入自定义 Filter

### Q9: Child-First ClassLoader 有什么风险？

**A:** Child-First 意味着灵元优先加载自己的类，可能导致：

1. **版本冲突**：灵元使用的库版本与灵核不同
2. **类型不兼容**：同一个类被不同 ClassLoader 加载

解决方案：
- 使用 Shared API 共享公共类
- 在 `ling.yml` 中声明依赖

---

## 三、使用问题

### Q10: 如何调试灵元代码？

**A:** 几种方式：

1. **远程调试**：启动时添加 `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005`
2. **开发模式**：设置 `dev-mode: true`，启用热更新
3. **日志调试**：调整日志级别到 DEBUG 或 TRACE

### Q11: 灵元之间如何通信？

**A:** 灵元之间通过服务接口通信：

```java
// 使用 @LingReference 注入其他灵元的服务
// 框架会在所有已安装的灵元中查找实现了该接口的 Bean
@LingReference
private UserService userService;
```

### Q12: 如何实现灰度发布？

**A:** 通过 Dashboard API 配置：

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "2.0.0"}'
```

详见 [Dashboard 文档](dashboard.md)。

### Q13: 如何处理灵元依赖？

**A:** 在 `ling.yml` 中声明依赖：

```yaml
dependencies:
  - user-ling
  - common-ling
```

灵珑会确保依赖的灵元先加载。

### Q14: 灵元可以使用哪些 Spring 特性？

**A:** 灵元可以使用大部分 Spring 特性：

- ✅ @Component / @Service / @Repository
- ✅ @Autowired 依赖注入
- ✅ @Value 配置注入
- ✅ @Transactional 事务
- ⚠️ @Configuration 需要注意 ClassLoader 隔离
- ✅ @SpringBootApplication 支持（Spring Boot 灵元的标准入口方式）

---

## 四、故障排查

### Q15: 灵元加载失败怎么办？

**A:** 检查以下方面：

1. **日志**：查看日志中的错误信息
2. **类路径**：确保灵元 JAR 包含所有必要类
3. **依赖**：确保依赖的灵元已加载
4. **权限**：确保灵元有必要的 capabilities

详细排查步骤见 [故障排查手册](troubleshooting.md)。

### Q16: 内存持续增长怎么办？

**A:** 可能原因：

1. **ClassLoader 泄漏**：检查灵元是否有静态集合未清理
2. **ThreadLocal 泄漏**：确保灵元停止时清理 ThreadLocal
3. **监听器泄漏**：使用 EventBus 而不是手动注册监听器

泄漏检测是运行时内建能力，无需额外配置开关。开发模式下（`dev-mode: true`）自动启用激进诊断（`DEV_AGGRESSIVE` / `DEV_BOUNDED`），生产模式下自动降级为被动观测（`PROD_PASSIVE`）：
```yaml
lingframe:
  dev-mode: true  # 开发模式自动启用激进泄漏诊断
```

### Q17: 熔断器一直打开怎么办？

**A:**

1. 检查下游服务是否正常
2. 调整熔断阈值（在 governance 配置中）
3. 通过 Dashboard 查看熔断器状态

### Q18: 如何查看灵元状态？

**A:** 通过 Dashboard API：

```bash
# 查看所有灵元
curl http://localhost:8888/lingframe/dashboard/lings

# 查看单个灵元
curl http://localhost:8888/lingframe/dashboard/lings/{lingId}
```

---

## 五、Dashboard 相关

### Q19: Dashboard 如何启用？

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

### Q20: Dashboard 安装接口为什么不能用？

**A:** 安装接口默认关闭，需要显式开启：

```yaml
lingframe:
  dashboard:
    install-enabled: true
```

### Q21: 热更新接口为什么返回 403？

**A:** 热更新接口只在开发模式可用：

```yaml
lingframe:
  dev-mode: true
```

---

## 六、其他问题

### Q22: 灵珑支持哪些 JDK 版本？

**A:**

| JDK 版本 | 支持程度 |
|----------|----------|
| JDK 8 | ✅ 支持（部分功能受限） |
| JDK 11 | ✅ 支持 |
| JDK 17 | ✅ 完全支持（推荐） |
| JDK 21 | ✅ 支持 |

### Q23: 灵珑支持哪些 Spring Boot 版本？

**A:**

| Spring Boot 版本 | 支持程度 |
|------------------|----------|
| 2.7.x | ✅ 支持（部分功能受限） |
| 3.0.x | ✅ 支持 |
| 3.1.x | ✅ 支持 |
| 3.2.x | ✅ 完全支持（推荐） |

### Q24: 如何参与灵珑开发？

**A:**

1. Fork 仓库
2. 阅读贡献指南
3. 提交 Pull Request

### Q25: 在哪里可以获取帮助？

**A:**

- **文档**：本目录下的文档
- **Issues**：提交问题反馈

### Q26: 灵珑的开源协议是什么？

**A:** Apache License 2.0，可以免费用于商业项目。

---

## 七、路线图相关

### Q27: Prometheus/Grafana 集成什么时候支持？

**A:** 当前已经支持 Micrometer 指标桥接。若宿主应用提供 `MeterRegistry`，LingFrame 会自动注册灵元健康指标与治理信号指标；若宿主再引入 `micrometer-registry-prometheus` 并暴露 `/actuator/prometheus`，即可直接被 Prometheus 抓取。示例可参考 `lingframe-example-lingcore-app`。

### Q28: 消息代理（Kafka/RabbitMQ）什么时候支持？

**A:** 在 Phase 5 生态完善阶段规划，详见 [路线图](roadmap.md)。
