# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 项目简介

灵珑（LingFrame）是一个面向长期运行系统的 JVM 运行时治理框架。核心能力：单进程内灵元隔离、热加载/规范热卸载、运行时治理（权限、审计、限流、熔断、灰度）、Dashboard 控制面。

**开发手册权威源**：[DEVELOPMENT_MANUAL.zh-CN.md](DEVELOPMENT_MANUAL.zh-CN.md)。如有冲突，以该手册为准。

---

## 构建与测试命令

```bash
# 完整构建
mvn clean install

# 跳过测试构建
mvn clean install -DskipTests

# 构建指定模块（含依赖）
mvn clean install -pl lingframe-core -am

# 运行所有测试
mvn test

# 运行指定测试类
mvn test -Dtest=LingManagerTest

# 运行指定模块测试
mvn test -pl lingframe-core

# 启动示例灵核应用
cd lingframe-examples/lingframe-example-lingcore-app && mvn spring-boot:run
```

开发配置：在 `application.yml` 中设置 `lingframe.dev-mode: true` 启用热重载监听。

---

## 模块职责边界

| 模块 | 职责 | 不允许 |
| --- | --- | --- |
| `lingframe-api` | 契约层：接口、注解、异常、安全抽象 | 放业务实现、重依赖 |
| `lingframe-core` | 治理内核：流水线、路由、状态机、生命周期、事件总线 | 依赖任何生态环境 |
| `lingframe-runtime` | Spring Boot 适配：Web 治理过滤器、Bean 拦截、Starter 装配 | 反向污染 `lingframe-core` 边界 |
| `lingframe-infrastructure` | 基础设施代理：DB/Cache 权限控制 | 让灵元直接穿透底层设施 |
| `lingframe-dashboard` | 治理控制面：生命周期操作、灰度、模拟、SSE 事件流 | 越权写入核心内部状态 |
| `lingframe-examples` | 示例灵核应用与灵元 | 生产代码 |

灵元只能依赖 `lingframe-api`，**禁止**依赖 `lingframe-core`。

---

## 架构核心模型

### 运行时双层状态机

- **实例层**（`LingInstance`）：单个灵元版本实例的真实生命周期阶段
- **运行时层**（`LingRuntime`）：灵元整体对外呈现的宏观状态
- 两层之间通过事件与快照联动，不互相直接改状态

### 七个关键角色

| 角色 | 作用 | 核心约束 |
| --- | --- | --- |
| `LingInstance` | 单个灵元实例承载体 | 对外不暴露状态机写权限 |
| `InstanceCoordinator` | 实例状态唯一写入口 | 只有它能推进实例状态 |
| `InstancePool` | 管理活跃成员、默认实例、濒死队列 | 只管成员关系，不管完整生命周期 |
| `LingRuntime` | 灵元运行时聚合体 | 对外只暴露只读视图 |
| `RuntimeCoordinator` | 运行时状态唯一写入口 | 只有它能推进 `RuntimeStatus` |
| `DefaultLingLifecycleEngine` | 部署、切换、卸载顺序编排 | 不能绕过 coordinator 直改状态 |
| `LingUnloadCoordinator` | 卸载清理、资源回收、泄漏检测 | 不能替代生命周期编排 |

### 调用流水线

`InvocationPipelineEngine` 是唯一治理执行路径，过滤器按序执行：

`TrafficMetricsFilter` → `MacroStateGuardFilter` → `CanaryRoutingFilter` → `ResilienceGovernanceFilter` → `ContextIsolationFilter` → `GovernanceDecisionFilter` → `PermissionGovernanceFilter` → `ThreadIsolationGovernanceFilter` → `TerminalInvokerFilter`

三种执行模式：`NORMAL`（真实执行）、`SIMULATION`（模拟）、`GOVERN_ONLY`（仅治理）。

各入口对应模式：灵元服务调用用 `NORMAL`，灵核 Bean 方法拦截用 `GOVERN_ONLY`，Dashboard 模拟用 `SIMULATION`。

### 类加载隔离

- `LingClassLoader`：Child-First 策略
- 白名单强制委派父加载器：`java.*`、`com.lingframe.api.*`、`org.slf4j.*`
- `Shared API` 是进程级公共契约：新包可热加载，已加载契约不支持热更新或热卸载，变更必须重启进程

---

## 编码规范（硬规则）

### 术语统一

| 正确 | 禁止 |
| --- | --- |
| 灵珑 / LingFrame | 插件平台、Ling 插件系统 |
| 灵核 | 宿主、Host |
| 灵元 | 插件、Plugin |

- 中文语境优先写「灵珑」；补充英文名时写「灵珑（LingFrame）」
- 英文语境用 `LingFrame`

### 注释与日志

- 代码注释：**中文**
- 日志输出：**英文**
- 测试展示名（`@DisplayName`）：**中文**

### 架构硬约束

- 禁止绕过 `InstanceCoordinator` / `RuntimeCoordinator` 直接改状态
- 禁止把写权限散回聚合对象、池对象、业务对象
- 禁止扩大字符串魔法键
- 禁止为兼容保留已确认错误的旧边界
- 禁止删除高价值设计注释、踩坑说明、风险提示

---

## 修改前检查清单

1. 识别本次改动属于哪一层：实例层、运行时层、成员层、编排层、卸载层、适配层或文档层
2. 确认谁有写权限、谁只读、谁编排（答不出则不应开始改代码）
3. 确认改动是否影响测试、日志、文档和术语

交付最低要求：代码守住边界 + 测试覆盖关键语义 + 文档同步 + 术语统一。

---

## 测试规范

- 测试展示名统一中文，优先使用 `@Nested + @DisplayName`
- 关键语义必须有测试，不只是流程测试
- 测试类命名：`{ClassName}Test.java`

---

## 灵元配置示例（ling.yml）

```yaml
id: user-ling
version: 1.0.0
mainClass: "com.example.UserLing"
governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
```

## 灵核全局配置示例（application.yml）

```yaml
lingframe:
  enabled: true
  dev-mode: true
  ling-home: "lings"
  runtime:
    default-timeout: 3s
    bulkhead-max-concurrent: 10
```
