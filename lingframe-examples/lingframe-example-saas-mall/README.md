# lingframe-example-saas-mall

> SaaS 多租户商城 —— 灵珑 v0.4 二维路由机制的最佳活样本。

本示例展示如何把一个单体 Spring Boot 商城应用（[`lingframe-example-ling-mall`](../lingframe-example-ling-mall)）渐进式拆解为「灵核磐石底座 + 多业务灵元扩展」的二维路由架构。

## 1. 模块组成

```
lingframe-example-saas-mall
├── lingframe-example-saas-api              # 显性契约层（5 个接口 + DTO）
├── lingframe-example-saas-lingcore         # 灵核：底座 + 3 个 Controller
├── lingframe-example-saas-ling-oauth       # 灵元：三方社交登录能力
├── lingframe-example-saas-ling-refund      # 灵元：VIP 极速退款决策
└── lingframe-example-saas-ling-seckill     # 灵元：秒杀削峰异步下单
```

### 1.1 saas-api：显性契约层

灵核与灵元之间的「跨类加载器公共契约」，定义 5 个业务接口：

| 接口 | 实现方 | 调用方 |
| --- | --- | --- |
| `OAuthAbility` | ling-oauth 的 `OAuthAbilityImpl` | 灵核 `SaasAuthController` |
| `RefundPolicy` | ling-refund 的 `VipRefundPolicyImpl` | 灵核 `SaasRefundController` |
| `SeckillAbility` | ling-seckill 的 `SeckillAbilityImpl` | 灵核 `SaasSeckillController` |
| `SaasOrderService` | 灵核 `SaasOrderServiceImpl` | ling-refund、ling-seckill |
| `SaasSeckillActiveQueryService` | 灵核 `SaasSeckillActiveQueryServiceImpl` | ling-seckill |

### 1.2 saas-lingcore：灵核磐石底座

- 复用 [`lingframe-example-ling-mall`](../lingframe-example-ling-mall) 全部底座（entity/mapper/service），实现「100% 复用既有投资」
- 3 个 Controller 用 `@LingReference` 注入灵元代理，对外暴露 SaaS 多租户 API
- 2 个 `@Service` 实现灵核侧契约，供灵元反向调用

### 1.3 三个业务灵元

每个灵元都是独立的 Spring Boot 子应用，有自己的 `LingApplication` 入口和 `ling.yml` 元数据：

- **ling-oauth**：三方社交登录 mock 实现，支持租户级访问限制
- **ling-refund**：VIP 租户极速退款规则，普通租户走灵核人工审批
- **ling-seckill**：秒杀削峰队列 + 异步下单，0 个 JDBC 直接依赖

## 2. 二维路由机制体现

本示例的 6 个 `@LingReference` 字段完整覆盖了二维路由的三种典型场景：

| 字段 | 调用方向 | 候选 provider | 选中 |
| --- | --- | --- | --- |
| `SaasRefundController.refundPolicy` | 灵核 → 灵元 | `ling-refund` (LING, 0) 唯一 | ✅ `VipRefundPolicyImpl` |
| `SaasAuthController.oauthAbility` | 灵核 → 灵元 | `ling-oauth` (LING, 0) 唯一 | ✅ `OAuthAbilityImpl` |
| `SaasSeckillController.seckillAbility` | 灵核 → 灵元 | `ling-seckill` (LING, 0) 唯一 | ✅ `SeckillAbilityImpl` |
| `VipRefundPolicyImpl.saasOrderService` | 灵元 → 灵核 | `lingcore` (CORE, 100) 唯一 | ✅ `SaasOrderServiceImpl` |
| `SeckillAbilityImpl.saasOrderService` | 灵元 → 灵核 | `lingcore` (CORE, 100) 唯一 | ✅ `SaasOrderServiceImpl` |
| `SeckillAbilityImpl.activeQueryService` | 灵元 → 灵核 | `lingcore` (CORE, 100) 唯一 | ✅ `SaasSeckillActiveQueryServiceImpl` |

> **关键点**：所有 `@LingReference` 字段都**未指定 lingId**，走默认的 `__provider__` 占位符路径。
> 单 provider 场景下 `ProviderWeightRouter` 短路返回，权重 0 的灵元 provider 仍能被唯一命中。

### 2.1 Provider 注册对照

| 维度 | 灵核侧 | 灵元侧 |
| --- | --- | --- |
| 注册器 | `LingCoreServiceRegistrarProcessor` | `SpringLingContainer.scanAndRegisterLingServices` |
| Bean 来源 | 灵核 ApplicationContext 中的 `@Service`/`@Component`/`@Repository` Bean | 灵元子容器中的 `@Component` Bean |
| 调用工厂 | `LingServiceRegistrar.forCore(...)` | `new LingServiceRegistrar(...)` |
| ProviderKind | `CORE` | `LING` |
| 默认权重 | 100（自动承接全量流量） | 0（需 Dashboard 显式配置才接流量） |
| lingId | `lingcore-app` | 各灵元自己的 id（如 `ling-oauth`） |
| FQSID 格式 | `lingcore-app:<接口全限定名>` | `<lingId>:<接口全限定名>` |

### 2.2 FQSID 路径示例

以 `SeckillAbilityImpl.saasOrderService.createOrder(...)` 为例，完整路由链路：

```
1. @LingReference（无 lingId）
   └─ LingReferenceInjector 注入 GlobalServiceRoutingProxy

2. 调用 saasOrderService.createOrder(...)
   └─ GlobalServiceRoutingProxy.resolveTargetLingId()
      ├─ targetLingId == null
      └─ 返回 "__provider__" 占位符

3. SmartServiceProxy 拼接 FQSID
   └─ serviceFQSID = "__provider__:com.lingframe.example.saas.api.SaasOrderService"

4. InvocationPipelineEngine 进入 Pipeline
   └─ ContractProviderRoutingFilter (L0 阶段)
      ├─ 识别 "__provider__:" 前缀
      ├─ 提取 contractId = "com.lingframe.example.saas.api.SaasOrderService"
      ├─ 查询 provider 索引：[(lingcore-app, CORE, 100)] 唯一
      ├─ ProviderWeightRouter.selectProvider() 短路返回 lingcore-app
      └─ ctx.setTargetLingId("lingcore-app") + ctx.setRuntime(灵核 runtime)

5. TerminalInvokerFilter 反射调用 SaasOrderServiceImpl.createOrder(...)
```

## 3. 运行方式

### 3.1 集成测试

直接运行 [`SaasMallIntegrationTest`](lingframe-example-saas-lingcore/src/test/java/com/lingframe/example/saas/SaasMallIntegrationTest.java)。

测试类的 `static` 块会自动设置 `-Dlingframe.ling-roots` 指向三个灵元的 target 目录，并设置 `-Dlingframe.preload-api-jars` 指向 saas-api 模块。Spring Boot 启动时由灵珑 starter 自动加载三个灵元并完成 `@LingReference` 注入。

测试用例覆盖：

- OAuth 灵元多租户路由（VIP 租户正常登录、限制租户被拦截）
- 秒杀灵元异步下单（排队 → 异步出队 → 写库成功）
- 退款灵元租户级分流（普通租户走人工、VIP 租户秒退）

### 3.2 独立运行

修改 `lingframe-example-saas-lingcore/src/main/resources/application.yaml`：

```yaml
lingframe:
  enabled: true
  ling-roots:
    - <绝对路径>/lingframe-example-saas-ling-oauth
    - <绝对路径>/lingframe-example-saas-ling-seckill
    - <绝对路径>/lingframe-example-saas-ling-refund
  preload-api-jars:
    - <绝对路径>/lingframe-example-saas-api
```

运行 [`SaasMallApplication`](lingframe-example-saas-lingcore/src/main/java/com/lingframe/example/saas/SaasMallApplication.java)，端口 8083。

## 4. 设计要点

### 4.1 灵元零 JDBC 依赖

ling-refund 和 ling-seckill **没有**任何 MyBatis-Plus Mapper 或 DataSource 依赖。所有数据访问通过 `@LingReference` 反向调用灵核的 `SaasOrderService` / `SaasSeckillActiveQueryService` 完成。

这保证了灵元可以独立编译、独立测试、独立热加载，与底座数据访问技术栈（MyBatis-Plus/JDBC/H2）完全解耦。

### 4.2 灵核 GOVERN_ONLY 治理

`application.yaml` 中：

```yaml
lingframe:
  ling-core-governance:
    enabled: false              # 默认不拦截灵核内部调用
    govern-internal-calls: true # 但开启内部调用埋点
```

灵核 Bean 之间的直接调用（如 `SaasRefundController` 调 `OrderService`）走 `GOVERN_ONLY` 模式，仅埋点不拦截；跨灵元调用（通过 `@LingReference`）走 `NORMAL` 模式，完整治理链。

## 5. 从单体到多灵元的迁移路径

本示例展示了「绞杀迁移」的标准 5 步法：

### Step 1：底座复用（已完成）

把单体应用 [`lingframe-example-ling-mall`](../lingframe-example-ling-mall) 作为 Maven 类路径依赖引入灵核，保留全部既有业务资产。

### Step 2：抽取显性契约（已完成）

把需要跨灵元调用的接口抽到独立模块 `lingframe-example-saas-api`，包含接口定义 + DTO。这是灵核与灵元之间的「跨类加载器公共契约」。

### Step 3：抽取业务灵元（已完成）

把特定业务能力（OAuth/退款/秒杀）抽成独立 Maven 模块，每个模块：

- 有自己的 `mainClass`（`@SpringBootApplication` 标注的启动类，灵珑装载器据此创建灵元 Spring 子容器）
- 有自己的 `ling.yml` 元数据（id/version/mainClass）
- 通过 `@Component` 把能力实现注册到灵元子容器
- 灵珑 starter 自动扫描注册为 `ProviderKind.LING` provider

> 注：灵元 mainClass 不强制实现 `Ling` 接口——`@SpringBootApplication` 即可。
> 实现 `Ling` 接口可选用于接收 `onStart/onStop` 生命周期回调，未实现时仅记 warn 不阻塞。

### Step 4：用 `@LingReference` 替代直接 Bean 注入（已完成）

灵核 Controller 把 `@Autowired` 替换为 `@LingReference`：

```java
// 改造前（单体内部调用）
@Autowired
private RefundPolicy refundPolicy;

// 改造后（跨灵元契约调用）
@LingReference
private RefundPolicy refundPolicy;
```

灵元侧反向调用灵核同理：

```java
// ling-seckill 的 SeckillAbilityImpl
@LingReference
private SaasOrderService saasOrderService;  // 灵元 → 灵核
```

### Step 5（可选）：Dashboard 配置 provider 权重做灰度（未演示）

当同一个契约有多个灵元 provider 实现时（如多版本灰度），通过 Dashboard 下发权重覆盖：

```java
providerWeightRouter.setProviderWeight(
    "com.lingframe.example.saas.api.RefundPolicy",  // contractId
    "ling-refund-v2",                                 // 新版本灵元 lingId
    30                                                // 30% 流量灰度
);
```

本示例为简单起见未演示此场景，但框架已完整支持。双版本灰度场景示例可参考
[`lingframe-example-ling-user`](../lingframe-example-ling-user) +
[`lingframe-example-ling-user-canary`](../lingframe-example-ling-user-canary)：
两者 `ling.yml` 中 `id` 相同（`user-ling`）、`version` 不同（`1.0.0` vs `1.1.0-canary`），
canary 版本通过 `properties.canary: true` 标记，由 `CanaryRoutingFilter` 按版本灰度。

## 6. 相关示例

- [lingframe-example-ling-mall](../lingframe-example-ling-mall/README.md) —— 老单体最小化改造为灵元的范例
- [lingframe-example-lingcore-app](../lingframe-example-lingcore-app) —— 灵核示例，演示多灵元加载与 Dashboard 治理
- [lingframe-example-ling-user](../lingframe-example-ling-user) + [lingframe-example-ling-user-canary](../lingframe-example-ling-user-canary) —— 双版本灰度场景示例
