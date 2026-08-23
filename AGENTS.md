# AGENTS.md

This file provides unified guidance to AI coding assistants when working with code in this repository.

---

## 项目简介

灵珑（LingFrame）是一个面向长期运行系统的 JVM 运行时治理框架。核心能力：单进程内灵元隔离、热加载/规范热卸载、运行时治理（权限、审计、限流、熔断、N元路由）、Dashboard 控制面。

当前版本：`0.4.0`（`lingframe-dependencies` 的 `revision`）。默认构建矩阵为 **Spring Boot 2.7 / JDK 8**；**Spring Boot 3.5 / JDK 17** 通过 `-Pspring-boot3` 切换。

### 规范权威链

| 来源 | 角色 |
| --- | --- |
| [DEVELOPMENT_MANUAL.md](docs/zh-CN/development-manual.md) | **开发规范唯一真源**；与旧文档/实现冲突时，以本手册 + 当前代码事实为准 |
| [AGENTS.md](AGENTS.md) | AI 助手统一工作入口（不重复手册全文） |
| [MANIFESTO.md](docs/zh-CN/manifesto.md) / [WHY.md](docs/zh-CN/why.md) | 风格与价值观冲突时的上位依据 |
| [docs/development/](docs/development/) | 内部开发区：`proposal` / `assessment` / `archive` **不得**当作现行规范；须与手册和代码交叉确认 |

本文件是 AI 工作摘要，不是规范本身。不要在公开文档或提交说明中引用未公开的内部规划材料。

---

## 构建与测试命令

```bash
# 完整构建（默认 spring-boot2 / JDK 8）
mvn clean install

# 跳过测试构建
mvn clean install -DskipTests

# 构建指定模块（含依赖）
mvn clean install -pl lingframe-core -am

# 运行所有测试
mvn test

# 运行指定模块测试
mvn test -pl lingframe-core

# 运行指定测试类 / 方法（示例类名真实存在）
mvn test -pl lingframe-core -Dtest=RuntimeCoordinatorTest
mvn test -pl lingframe-core -Dtest=RuntimeCoordinatorTest#registerInitialInactive

# 与 CI 对齐：SB2 + 集成检查 profile（checkstyle / spotbugs 挂在 verify，不只在 test）
# 注意：须显式带上 -Pspring-boot2，单独 -Pintegration-check 会停用 activeByDefault 的 spring-boot2，
# 导致 springdoc artifactId 解析失败（见 lingframe-example-lingcore-app/pom.xml profile 注释）
mvn -B clean verify -Pspring-boot2,integration-check

# Spring Boot 3 / JDK 17 矩阵
mvn -B clean verify -Pspring-boot3

# 质量门控（也可单独执行）
mvn checkstyle:check
mvn spotbugs:check
mvn -B jacoco:check -pl lingframe-core,lingframe-dashboard,lingframe-runtime/lingframe-spring-boot-starter

# 示例灵核（最短路径）
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am package -DskipTests
cd lingframe-examples/lingframe-example-lingcore-app && mvn spring-boot:run
# 默认 http://localhost:8888 ，Dashboard: /dashboard.html

# 可选集成回归（见 [最短上手](docs/zh-CN/quick-start.md)）
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am "-Pspring-boot2,integration-check" verify "-Dit.test=ObservabilityClosedLoopIntegrationTest"

# JMH 基准（非默认模块）
mvn -pl lingframe-benchmark package -Pbenchmark -DskipTests
```

- 根模块：`lingframe-dependencies`、`lingframe-bom`、`lingframe-api`、`lingframe-core`、`lingframe-runtime`、`lingframe-infrastructure`、`lingframe-examples`、`lingframe-dashboard`；`lingframe-benchmark` 仅 `-Pbenchmark`。
- 质量插件已挂：`checkstyle`、`spotbugs`、`jacoco`；日常以 `verify` 为准。
- 开发配置：`application.yml` 中 `lingframe.dev-mode: true` 启用热重载监听；`ling-home` 指向灵元目录。

---

## 模块职责边界

| 模块 | 职责 | 不允许 |
| --- | --- | --- |
| `lingframe-api` | 契约层：接口、注解、异常、安全抽象 | 放业务实现、重依赖 |
| `lingframe-core` | 治理内核：流水线、路由、状态机、生命周期、事件总线 | 依赖任何生态环境（不以 Spring 为设计前提） |
| `lingframe-runtime` | 运行时适配：公共 `spring-boot-starter` + 栈专属 `spring-boot2/3-starter` + `native`；Web 治理过滤器、Bean 拦截 | 反向污染 `lingframe-core`；禁止在公共 starter 反射探测 Servlet |
| `lingframe-infrastructure` | 基础设施代理：`infra-storage` / `infra-cache` 等 | 让灵元直接穿透底层设施 |
| `lingframe-dashboard` | 治理控制面：生命周期、灰度、模拟、SSE；**单 GAV**，Servlet 差异在 `java-javax` / `java-jakarta` 矩阵源码集 | 越权写入核心内部状态；禁止拆 dashboard-boot2/3 双坐标 |
| `lingframe-examples` | 示例灵核应用与灵元 | 生产代码 / 架构事实来源 |

灵元只能依赖 `lingframe-api`，**禁止**依赖 `lingframe-core`。

**双栈（摘要，细则见手册第 5.2 节）**：默认 `-Pspring-boot2`（JDK 8，示例主路径）；`-Pspring-boot3`（JDK 17，支持线）。Runtime 用双 starter 类型化差异；Dashboard 用单 artifact + `build-helper` 矩阵源码集。切换矩阵务必 `clean`。

定位入口（不全列类树）：

- `com.lingframe.core.ling` — 实例 / 运行时 / 生命周期
- `com.lingframe.core.fsm` — 状态机
- `com.lingframe.core.pipeline` — 治理流水线
- `com.lingframe.core.classloader` / `security` — 隔离与校验
- `com.lingframe.core.spi` — 扩展点
- `com.lingframe.api.*` — 对外契约

---

## 架构核心模型

### 运行时双层状态机

- **实例层**（`LingInstance` / `InstanceStatus`）：单个灵元版本实例的真实生命周期阶段
- **运行时层**（`LingRuntime` / `RuntimeStatus`）：灵元整体对外呈现的宏观状态
- 两层通过**事件与快照**联动，**不互相直接改状态**

### 写权限真源（改前必须答出）

| 概念 | 唯一真源 | 唯一写入口 | 其他角色 |
| --- | --- | --- | --- |
| 实例状态 | `LingInstance` 内部状态机 | `InstanceCoordinator` | 其他对象只能读或响应事件 |
| 运行时状态 | `RuntimeCoordinator` 内部 FSM / 快照 | `RuntimeCoordinator` | `LingRuntime` 只读 |
| 实例成员关系 | `InstancePool` | 受编排驱动变更 | 不管完整生命周期 |
| 生命周期阶段顺序 | `DefaultLingLifecycleEngine` | 编排逻辑本身 | 不能跳过 coordinator 直改状态 |
| 卸载清理 | `LingUnloadCoordinator` | 清理协调器 | 不能替代生命周期编排 |
| 迁移阶段 | `MigrationStateHolder` | `DefaultLingLifecycleEngine` 编排 + `confirmPhaseTransition` 显式确认 | 其他对象只能读或响应事件 |

### 七个关键角色

| 角色 | 作用 | 核心约束 |
| --- | --- | --- |
| `LingInstance` | 单个灵元实例承载体 | 对外不暴露状态机写权限 |
| `InstanceCoordinator` | 实例状态唯一写入口 | 只有它能推进实例状态 |
| `InstancePool` | 管理活跃成员、默认实例、濒死队列 | 只管成员关系，不做生命周期总控 |
| `LingRuntime` | 灵元运行时聚合体 | 对外只暴露只读视图 |
| `RuntimeCoordinator` | 运行时状态唯一写入口 | 只有它能推进 `RuntimeStatus` |
| `DefaultLingLifecycleEngine` | 部署、切换、卸载顺序编排 | 不能绕过 coordinator 直改状态 |
| `LingUnloadCoordinator` | 卸载清理、资源回收、泄漏检测 | 不能替代生命周期编排 |

### 调用流水线

`InvocationPipelineEngine` 是治理主链；内置过滤器按序执行（以 `PipelineArchitectureContractTest` / `FilterRegistry` 为准）：

`ContractProviderRoutingFilter` → `TrafficMetricsFilter` → `MacroStateGuardFilter` → `InstanceRoutingFilter` → `InvocationPolicyPrefillFilter` → `ResilienceGovernanceFilter` → `ContextIsolationFilter` → `GovernanceDecisionFilter` → `PermissionGovernanceFilter` → `ThreadIsolationGovernanceFilter` → `TerminalInvokerFilter`

L0 provider 路由 / L1 实例路由分层：`InstanceRoutingFilter` 承接 provider 路由已设置的 `ctx.runtime`，位于 `MacroStateGuardFilter` 之后、`InvocationPolicyPrefillFilter` 之前。

**路由层去身份化**：路由层只认 `weight` 和方法资格，不引用实现方身份（灵核/灵元）。身份在注册时沉淀为 `weight` 数值（灵核默认 100，灵元默认 0），方法资格通过 `LingServiceRegistry.hasMethod` 判定——未声明被调用方法的 provider 被剔除，方法级 fallback 是路由的副产物。

**N元权重分流**：同一契约同一时刻允许多 provider 共存，由 `ProviderWeightRouter` 按权重比例随机分配（二元只是 N=2 的特例，N≥3 即多版本共存/多租户场景）——`DefaultLingServiceRegistry.registerProvider` 允许任意 N 个 provider 注册，`ProviderWeightRouter.selectProvider` 候选数 > 2 时仅「候选数变化」时告警一次，不主动抛异常强打断业务。

**迁移状态机**：`MigrationPhase`（`CORE_EXCLUSIVE` / `MIGRATING` / `LING_EXCLUSIVE` / `ITERATING`）+ `MigrationStateHolder` 归属 `core.routing` 包，与路由器同包表达"迁移阶段是路由层的元状态"，不入侵运行时 FSM。详见 `development-manual.md` §6.8。

三种执行模式：`NORMAL`（真实执行）、`SIMULATION`（模拟）、`GOVERN_ONLY`（仅治理）。

入口说明：
- 灵元 IPC / 服务调用：`NORMAL` 全链 + `TerminalInvokerFilter`
- Web / 灵核 Bean 拦截：`GOVERN_ONLY` 跑治理链后由灵核侧 Web/AOP 框架路径继续业务执行（非 pipeline terminal）
- Dashboard 模拟：`SIMULATION`

SPI/动态过滤器不得占用内置 order 保留位。

`InvocationContext` 已分区（routing / resolution / governance / execution），**禁止扩大字符串魔法键**承载核心语义。

### 类加载与 Shared API

- `LingClassLoader`：Child-First；白名单强制委派父加载器（含 `java.*`、`com.lingframe.api.*`、`org.slf4j.*` 等）
- Spring 等生态包的父委派由 **runtime** 注入，core 不绑定灵核应用栈
- `Shared API` 是进程级公共契约（接口 / DTO / 必要注解），不是共享业务实现
- **全新 Shared API JAR 可热加载**；**已进入共享边界的 JAR 不允许热更新或热卸载**；替换/破坏性变更必须**重启进程**
- `SharedApiManager` 启动边界：预加载 → 注册包前缀 → **冻结** → 再加载灵元
- 类加载权威：`LingInstance.getClassLoader()`，不要把 TCCL 当隔离真源
- 隔离边界诚实表述见 `development-manual.md` §6.4.5。代码注释 / 文档 / 提交说明里**禁止**写「完全隔离」「绝对隔离」「架构保证零引用」「永不进入灵核静态域」；正确表述是「类型隔离」「编排隔离」「卸载后可证 GC」「BeanFactory 层隔离」

---

## 编码规范（硬规则）

### 术语统一

| 正确 | 禁止 |
| --- | --- |
| 灵珑 / LingFrame | 插件平台、Ling 插件系统 |
| 灵核 / LingCore | 宿主、Host |
| 灵元 / Ling | 插件、Plugin |

- 中文语境优先写「灵珑」；补充英文名时写「灵珑（LingFrame）」，不要写成「LingFrame（灵珑）」
- 英文语境用 `LingFrame`；英文单元名用 `LingCore` / `Ling`

### 注释与日志

- 代码注释：**中文**
- 日志输出：**英文**
- 测试展示名（`@DisplayName`）：**中文**；优先 `@Nested + @DisplayName`

### 架构硬约束

- 禁止绕过 `InstanceCoordinator` / `RuntimeCoordinator` 直接改状态
- 禁止把写权限散回聚合对象、池对象、业务对象
- 禁止让 `LingRuntime` 再持有第二份 runtime FSM
- 禁止扩大字符串魔法键 / 隐式状态
- 禁止为兼容保留已确认错误的旧边界
- 禁止删除高价值设计注释、踩坑说明、风险提示
- **职责分职（禁止混用）**：
  - **切流 / 停流** → N 元路由、迁移权重、契约权重
  - **启停授权** → `LING_ENABLE` 等 capability
  - **RuntimeStatus** → 实例聚合**事实**，禁止用状态机表达切流
  - **真下线回收** → 卸载（STOPPING → REMOVED）
- **RuntimeCoordinator.register 时序**：实例状态事件出现前必须先 `register(lingId)`（生产：`ensureRuntimeForDeployment`）；禁止依赖事件防御性 register
- **DB 治理边界**：存储权限主要覆盖 **Spring DataSource Bean 代理路径**；`DriverManager` / 手搓连接 / 非 Bean 池可绕过——模型边界须诚实，禁止吹成全路径沙箱
- 反射 / JVM 深水区补丁仅在必要时使用，必须封装并配套风险说明、测试与可观测性
- 治理语义（timeout、permission、audit、unload、routing fallback、状态含义等）必须可证明：有归属、有失败路径、有日志/事件/测试

---

## 修改前检查清单

1. 识别本次改动属于哪一层：实例层、运行时层、成员层、编排层、卸载层、适配层或文档层
2. 确认谁有写权限、谁只读、谁编排（**答不出则不应开始改代码**）
3. 确认改动是否影响测试、日志、文档和术语

交付最低要求：代码守住边界 + 测试覆盖关键语义 + 文档同步 + 术语统一 + 没有新增隐式状态和魔法键扩散。

涉及架构边界、状态机、生命周期顺序、Shared API 规则、测试/AI 规则的改动，必须同步更新测试和文档。

---

## 测试规范

- 默认 JUnit 5；需要 mock 时用 Mockito
- 测试展示名统一中文，优先使用 `@Nested + @DisplayName`
- 关键语义必须有测试，不只是流程 / happy path
- 测试类命名：`{ClassName}Test.java`

涉及以下内容时优先补测试：状态机迁移、生命周期编排顺序、多版本切换、濒死队列/排空/回收、timeout、permission/audit、routing、pipeline 顺序、classloader 边界、Shared API 冻结语义、并发安全、卸载后资源清理。

| 变更类型 | 至少需要的测试 |
| --- | --- |
| 状态机 | 合法迁移 + 非法迁移 |
| 生命周期编排 | 顺序 + 失败/中断 |
| Filter 顺序 | Pipeline 契约 |
| 卸载与回收 | 资源回收 + 长时间运行退化风险 |
| 权限/超时/审计 | 成功 / 拒绝 / 回退 / 审计 |

---

## 配置示例

灵元 `ling.yml`：

```yaml
id: user-ling
version: 1.0.0
mainClass: "com.example.UserLing"
governance:
  permissions:
    - methodPattern: "storage:sql"
      permissionId: "READ"
```

灵核 `application.yml`：

```yaml
lingframe:
  enabled: true
  dev-mode: true
  ling-home: "lings"
  runtime:
    default-timeout: 3s
    bulkhead-max-concurrent: 10
```
