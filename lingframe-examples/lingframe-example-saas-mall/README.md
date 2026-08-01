# lingframe-example-saas-mall

> SaaS 多租户商城 —— 灵珑 v0.4 二维路由机制的最佳活样本。

本示例展示如何把一个单体 Spring Boot 商城应用（[`lingframe-example-ling-mall`](../lingframe-example-ling-mall)）渐进式拆解为“灵核磐石底座 + 多业务灵元扩展”的二维路由架构。

核心定位：**绞杀迁移示例**——契约就是 ling-mall 原生接口的全限定名，不发明新中间契约；灵核零新业务代码、零业务 HTTP 入口；灵元提供三种维度的实现（覆盖 / 拓展 / 新增），通过双 provider 权重切流演示流量从灵核老实现迁移到灵元新实现的全过程。

## 1. 模块组成

```
lingframe-example-saas-mall
├── lingframe-example-saas-lingcore         # 灵核：底座（复用 ling-mall 全部资产）
├── lingframe-example-saas-ling-oauth       # 灵元：覆盖维度——覆盖 UserService.socialLogin
├── lingframe-example-saas-ling-seckill     # 灵元：拓展维度——拓展 SeckillService.seckill
└── lingframe-example-saas-ling-inventory      # 灵元：新增维度——带 TTL 的库存预占（灵核不存在的契约）
```

> **不再有 saas-api 模块**：灵核与灵元之间的跨类加载器公共契约就是 ling-mall 原生接口（`UserService` / `SeckillService` / `InventoryService` 等），由灵元 pom 以 `provided` 依赖 ling-mall 获取。运行期灵元 `LingClassLoader` 父回退到灵核 ClassLoader 解析这些接口，灵核与灵元看到同一 `Class` 对象，身份一致。ling-mall 是“混合契约+实现”包，整包预载会过度共享灵核实现类，故**不**使用 `preload-api-jars` 显式预载。

### 1.1 saas-lingcore：灵核磐石底座

- 复用 [`lingframe-example-ling-mall`](../lingframe-example-ling-mall) 全部底座（entity/mapper/service/Controller），实现“100% 复用既有投资”
- ling-mall 的 `@Service`（`UserServiceImpl` / `OrderServiceImpl` / `SeckillServiceImpl` / `InventoryServiceImpl` 等）被灵核 Spring 扫描后，由 `LingServiceRegistrar.forCore` 按接口全限定名注册为 CORE provider（weight=100），自动承接全量流量
- 灵核不编写任何新业务代码、不持有业务 HTTP 入口、不直接操作迁移状态机
- 灵核老 HTTP 入口（ling-mall 的 `AuthController` / `OrderPortalController` 等）保留，体现迁移前形态

### 1.2 三个业务灵元

每个灵元都是独立的 Spring Boot 子应用，有自己的 `LingApplication` 入口和 `ling.yml` 元数据。三个灵元体现绞杀迁移的三种维度：

| 灵元 | 维度 | 实现的契约 | 与灵核的关系 |
| --- | --- | --- | --- |
| **ling-oauth** | 覆盖 | `UserService`（ling-mall 原生） | 灵核有同契约实现，双 provider 权重切流；灵元覆盖 `socialLogin` 叠加 SaaS 多租户治理 |
| **ling-seckill** | 拓展 | `SeckillService`（ling-mall 原生） | 灵核有同契约实现，双 provider 权重切流；灵元拓展 `seckill` 叠加租户级配额预检 |
| **ling-inventory** | 新增 | `InventoryHoldService`（灵元自定义） | 灵核无此契约，灵元是唯一 provider；提供带 TTL 的库存预占能力，卸载后能力消失 |

### 1.3 灵元自暴露 HTTP 入口

每个灵元自带 `@RestController`，由 `WebInterfaceManager` 注册到灵核 Spring MVC。灵核 Spring MVC 只持有 `LingWebEntryHandler`（灵核类），不接触灵元类，保证灵元可热卸载。

| 灵元 | Controller | 路径前缀 | 内层 `@LingReference` |
| --- | --- | --- | --- |
| ling-oauth | `SaaSAuthController` | `/api/saas/ling/auth` | `UserService`（双 provider 切流） |
| ling-seckill | `SaaSSeckillController` | `/api/saas/ling/seckill` | `SeckillService`（双 provider 切流） |
| ling-inventory | `InventoryHoldController` | `/api/saas/ling/inventory` | `InventoryHoldService`（灵元唯一 provider） |

## 2. 二维路由机制体现

本示例的 `@LingReference` 字段覆盖二维路由的三种典型场景：

| 字段 | 调用方向 | 候选 provider | 选中 |
| --- | --- | --- | --- |
| `SaaSAuthController.userService` | 灵核 → 灵元/灵核 | `lingcore` (100) + `saas-oauth-ling` (0) | 默认灵核；切流后灵元 |
| `SaaSSeckillController.seckillService` | 灵核 → 灵元/灵核 | `lingcore` (100) + `saas-seckill-ling` (0) | 默认灵核；切流后灵元 |
| `InventoryHoldController.inventoryHoldService` | 灵核 → 灵元 | `saas-inventory-hold-ling` 唯一 | ✅ 灵元（灵核无此契约） |
| `SaaSUserServiceImpl.coreUserService` | 灵元 → 灵核 | `lingcore` 唯一（显式 pinning） | ✅ 灵核 `UserServiceImpl` |
| `SaaSSeckillServiceImpl.coreSeckillService` | 灵元 → 灵核 | `lingcore` 唯一（显式 pinning） | ✅ 灵核 `SeckillServiceImpl` |
| `InventoryHoldServiceImpl.coreInventoryService` | 灵元 → 灵核 | `lingcore` 唯一（显式 pinning） | ✅ 灵核 `InventoryServiceImpl` |

> **关键点**：
> - 灵核 Controller 内 `@LingReference` **不指定 lingId**，走默认裸 contractId 路由路径，由 `ProviderWeightRouter` 按权重在灵核/灵元之间切流。
> - 灵元内 `@LingReference(lingId="lingcore-app")` **显式 pinning 到灵核**，绕过双 provider 权重路由，避免“灵元 → 灵元”自调用循环。

### 2.1 Provider 注册对照

| 维度 | 灵核侧 | 灵元侧 |
| --- | --- | --- |
| 注册器 | `LingCoreServiceRegistrarProcessor` | `SpringLingContainer.scanAndRegisterLingServices` |
| Bean 来源 | 灵核 ApplicationContext 中的 ling-mall `@Service` Bean | 灵元子容器中的 `@Component` Bean |
| 调用工厂 | `LingServiceRegistrar.forCore(...)` | `new LingServiceRegistrar(...)` |
| 默认权重 | 100（自动承接全量流量） | 0（需 Dashboard 显式配置才接流量） |
| lingId | `lingcore-app` | 各灵元自己的 id（如 `saas-oauth-ling`） |
| FQSID 格式 | `lingcore-app:<接口全限定名>` | `<lingId>:<接口全限定名>` |

### 2.2 FQSID 路径示例

以 `SaaSAuthController.userService.socialLogin(...)` 切流到灵元为例，完整路由链路：

```
1. @LingReference（无 lingId）
   └─ LingReferenceInjector 注入 GlobalServiceRoutingProxy

2. 调用 userService.socialLogin(...)
   └─ GlobalServiceRoutingProxy.resolveTargetLingId()
      └─ targetLingId == null → 走默认裸 contractId 路由路径

3. SmartServiceProxy 组装 FQSID
   └─ serviceFQSID = "com.lingframe.example.mall.service.UserService"（裸 contractId）

4. InvocationPipelineEngine 进入 Pipeline
   └─ ContractProviderRoutingFilter (L0 阶段)
      ├─ targetLingId == null 触发 L0 路由
      ├─ contractId = "com.lingframe.example.mall.service.UserService"
      ├─ 查询 provider 索引：[(lingcore-app, 100), (saas-oauth-ling, 0)]
      ├─ ProviderWeightRouter.selectProvider() 按权重选
      └─ 默认返回 lingcore-app；Dashboard 调权重后返回 saas-oauth-ling

5. TerminalInvokerFilter 反射调用对应 UserServiceImpl.socialLogin(...)
```

灵元内 `@LingReference(lingId="lingcore-app")` 的链路差别在第 2 步：`targetLingId = "lingcore-app"`，跳过 L0 权重路由，直接由 `InstanceRoutingFilter.routeCoreTarget` 从灵核单例池取实例设为 `targetInstance`（详见 [方案文档第七节](../../.trae/documents/saas-mall绞杀迁移重做方案.md)）。

## 3. 灵元实现 IService 契约的 delegate 抽象层

ling-mall 的 `UserService` / `SeckillService` 等接口 `extends MyBatis-Plus IService<T>`，灵元实现这些接口时被迫实现 `IService` 的全部抽象方法（`saveBatch` / `getBaseMapper` / `getEntityClass` 等 9 个）。灵元不持有 DataSource，这些方法只能 delegate 到灵核。

为消除每个灵元重复写 delegate 桩代码，本示例引入公共抽象基类：

[`DelegatingIServiceSupport<T>`](../../lingframe-infrastructure/lingframe-infra-mybatis-plus/src/main/java/com/lingframe/infra/mybatisplus/DelegatingIServiceSupport.java)（位于 `lingframe-infra-mybatis-plus` 模块）

**设计约束**：
- 零强引用：基类不持有灵核 Bean，子类通过 `getCoreService()` 返回 `@LingReference` 注入的代理（代理本身是零强引用设计，只持字符串元数据）
- 不引入 ClassLoader 泄漏：静态继承，不生成代理类，无需配套 Cleaner
- 不依赖 Spring：纯抽象类，可在任意上下文使用
- 适用范围：任何用到 MyBatis-Plus 的灵元 delegate 灵核 IService 的场景

**子类示例**（ling-oauth 的 `SaaSUserServiceImpl`）：

```java
@Component
public class SaaSUserServiceImpl extends DelegatingIServiceSupport<User> implements UserService {

    @LingReference(lingId = "lingcore-app")
    private UserService coreUserService;

    @Override
    protected IService<User> getCoreService() {
        return coreUserService;
    }

    // 只写覆盖点，零 IService 桩代码
    @Override
    public String socialLogin(String platform, String openId, String nickname, String avatar) {
        // SaaS 多租户治理逻辑 ...
        return coreUserService.socialLogin(platform, tenantScopedOpenId, nickname, avatar);
    }

    // 非覆盖点：直接 delegate 灵核
    @Override
    public String login(LoginRequest req) { return coreUserService.login(req); }
}
```

> **“新增维度”灵元**（ling-inventory 的 `InventoryHoldServiceImpl`）自定义 `InventoryHoldService` 接口，**不** `extends IService`，自然无需继承 `DelegatingIServiceSupport`——这是“新增功能灵元”的设计优势，灵元不依赖灵核接口契约。

## 4. 运行方式

### 4.1 集成测试

直接运行 [`SaasMallIntegrationTest`](lingframe-example-saas-lingcore/src/test/java/com/lingframe/example/saas/SaasMallIntegrationTest.java)。

测试类的 `static` 块会自动设置 `-Dlingframe.ling-roots` 指向三个灵元的 target 目录。Spring Boot 启动时由灵珑 starter 自动加载三个灵元并完成 `@LingReference` 注入。

测试用例覆盖（按 `@Order` 顺序）：

1. **OAuth 灵元覆盖 socialLogin**：默认走灵核；切流后 `tenant_block` 被灵元拦截、`tenant_vip` 走灵元 delegate 灵核
2. **Seckill 灵元拓展 seckill**：默认走灵核；切流后 `tenant_block` 被灵元拦截、`tenant_vip` 走灵元配额预检 + delegate 灵核
3. **InventoryHold 灵元新增能力**：预占 → 状态查询 → 确认扣减 → 重复确认失败 → 释放失败 → 不存在单据，全链路验证
4. **灵元卸载回退**：卸载 OAuth 灵元后，`tenant_block` 不再被拦截，流量自动回退灵核

另有 [`SaasMallGovernanceObservabilityTest`](lingframe-example-saas-lingcore/src/test/java/com/lingframe/example/saas/SaasMallGovernanceObservabilityTest.java) 验证治理可观测性：基于 `UserService` 契约下发治理补丁，灵元权重调到 100 后限流触发。

### 4.2 独立运行

修改 `lingframe-example-saas-lingcore/src/main/resources/application.yaml`：

```yaml
lingframe:
  enabled: true
  ling-roots:
    - ../lingframe-example-saas-ling-oauth
    - ../lingframe-example-saas-ling-seckill
    - ../lingframe-example-saas-ling-inventory
```

运行 [`SaasMallApplication`](lingframe-example-saas-lingcore/src/main/java/com/lingframe/example/saas/SaasMallApplication.java)，端口 8083，Dashboard: `/dashboard.html`。

## 5. 设计要点

### 5.1 灵元零 JDBC 依赖

三个灵元**没有**任何 MyBatis-Plus Mapper 或 DataSource 依赖。所有数据访问通过 `@LingReference` 反向调用灵核的 `UserService` / `SeckillService` / `InventoryService` 完成。

这保证了灵元可以独立编译、独立测试、独立热加载，与底座数据访问技术栈（MyBatis-Plus/JDBC/H2）完全解耦。

### 5.2 灵核 GOVERN_ONLY 治理

`application.yaml` 中：

```yaml
lingframe:
  ling-core-governance:
    enabled: false              # 默认不拦截灵核内部调用
    govern-internal-calls: true # 但开启内部调用埋点
```

灵核 Bean 之间的直接调用走 `GOVERN_ONLY` 模式，仅埋点不拦截；跨灵元调用（通过 `@LingReference`）走 `NORMAL` 模式，完整治理链。

### 5.3 多租户 tenantId 走请求头 label

tenantId **不进接口签名**（ling-mall 老接口零改动），由灵元 HTTP 入口读请求头 `X-Tenant-Id` 写入 `LingCallContext.setLabels`，路由层 `selectByLabels` 精准命中带租户标签的灵元 provider；灵元 Service 内部从 `LingCallContext.getLabels().get("tenant")` 取 tenantId 做租户级决策。请求结束由 `LingWebGovernanceFilter` 统一 `clear`，无 ThreadLocal 泄漏。

## 6. 从单体到多灵元的迁移路径

本示例展示了“绞杀迁移”的标准 5 步法：

### Step 1：底座复用（已完成）

把单体应用 [`lingframe-example-ling-mall`](../lingframe-example-ling-mall) 作为 Maven 类路径依赖引入灵核，保留全部既有业务资产。

### Step 2：灵核 provider 自动注册（已完成）

ling-mall 的 `@Service` 被灵核 Spring 扫描后，由 `LingServiceRegistrar.forCore` 按接口全限定名注册为 CORE provider（weight=100）。这是“零 core 新代码”的关键——灵核不需要写任何适配层。

### Step 3：抽取业务灵元（已完成）

把特定业务能力（OAuth/秒杀/库存预占）抽成独立 Maven 模块，每个模块：

- 有自己的 `mainClass`（`@SpringBootApplication` 标注的启动类，灵珑装载器据此创建灵元 Spring 子容器）
- 有自己的 `ling.yml` 元数据（id/version/mainClass）
- 通过 `@Component` 把能力实现注册到灵元子容器
- 灵珑 starter 自动扫描注册为 `ProviderKind.LING` provider（weight=0）
- 灵元 pom 以 `provided` 依赖 ling-mall，编译期获取原生接口，运行期不打包

> 注：灵元 mainClass 不强制实现 `Ling` 接口——`@SpringBootApplication` 即可。
> 实现 `Ling` 接口可选用于接收 `onStart/onStop` 生命周期回调，未实现时仅记 warn 不阻塞。

### Step 4：用 `@LingReference` 替代直接 Bean 注入（已完成）

灵核 Controller 把 `@Autowired` 替换为 `@LingReference`：

```java
// 改造前（单体内部调用）
@Autowired
private UserService userService;

// 改造后（跨灵元契约调用，走路由层双 provider 切流）
@LingReference
private UserService userService;
```

灵元侧反向调用灵核同理，但需**显式 pinning 到灵核**避免自调用循环：

```java
// ling-oauth 的 SaaSUserServiceImpl
@LingReference(lingId = "lingcore-app")
private UserService coreUserService;  // 灵元 → 灵核
```

### Step 5（可选）：Dashboard 配置 provider 权重做灰度

当同一个契约有灵核 + 灵元双 provider 时，通过 Dashboard 下发权重覆盖完成流量迁移：

```java
providerWeightRouter.setProviderWeight(
    "com.lingframe.example.mall.service.UserService",  // contractId
    "saas-oauth-ling",                                  // 灵元 lingId
    100                                                 // 100% 流量切到灵元
);
providerWeightRouter.setProviderWeight(
    "com.lingframe.example.mall.service.UserService",
    "lingcore-app",                                     // 灵核 lingId
    0                                                   // 灵核降级为 0
);
```

本示例的集成测试 `switchToLing(...)` 即用此机制完成切流验证。双版本灰度场景示例可参考
[`lingframe-example-ling-user`](../lingframe-example-ling-user) +
[`lingframe-example-ling-user-canary`](../lingframe-example-ling-user-canary)：
两者 `ling.yml` 中 `id` 相同（`user-ling`）、`version` 不同（`1.0.0` vs `1.1.0-canary`），
canary 版本通过 `properties.canary: true` 标记，由 `CanaryRoutingFilter` 按版本灰度。

## 7. 相关示例

- [lingframe-example-ling-mall](../../lingframe-example-ling-mall/zh-CN/README.md) —— 老单体最小化改造为灵元的范例
- [lingframe-example-lingcore-app](../lingframe-example-lingcore-app) —— 灵核示例，演示多灵元加载与 Dashboard 治理
- [lingframe-example-ling-user](../lingframe-example-ling-user) + [lingframe-example-ling-user-canary](../lingframe-example-ling-user-canary) —— 双版本灰度场景示例
- [方案文档](../../.trae/documents/saas-mall绞杀迁移重做方案.md) —— 本示例的完整重做方案与内核必要改动说明
