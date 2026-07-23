# 生产硬化配置清单

面向把灵珑装进**真实生产灵核**时的最小硬化建议。  
不替代安全审计；只列可立即落地的配置与运维动作。

> 术语：使用“灵核 / 灵元”，不要使用“宿主 / 插件”。

---

## 1. Dashboard 访问控制

```yaml
lingframe:
  dashboard:
    enabled: true
    access-token:
      enabled: true
      # 使用足够长的随机串，勿用示例默认值
      token: "<strong-unique-token>"
      # 生产必须 false：弱口令启动失败
      allow-weak: false
```

- 示例项目可保留 `token: "123456"` 且 `allow-weak: true`，**不要**原样上生产。
- Token 只走 Header：`X-Access-Token`。

---

## 2. 灵核治理姿态

默认值为兼容本地/历史装配，**生产请显式打开**：

```yaml
lingframe:
  dev-mode: false
  # 若配置了 mode-switch-password，可通过 Dashboard 切换模式（需密码）
  # mode-switch-password: "<switch-password>"

  ling-core-governance:
    enabled: true              # 灵核 Web/Bean 走治理
    check-permissions: true    # 灵核身份也走权限表（按需要）
    # govern-internal-calls: false  # 是否治理灵核自调用，按业务决定

  security:
    strict-mode: true          # 危险 API 扫描更严（安装期）
```

说明：

- 非 `dev-mode` 且治理关闭时，`LingFrameConfig.init` 会打 **WARN**，提醒硬化。
- 危险 API 扫描是**加载期**信号，**不是**完整 JVM 沙箱。见 [Shared API 规范](shared-api-guidelines.md)。

---

## 3. 卸载 / drain

```yaml
lingframe:
  runtime:
    # drain 最长等待（秒）
    force-cleanup-delay: 30s
    # true（默认）：超时后强制 tearDown，可能打断在途请求，日志 [FORCE_DRAIN]
    # false：超时仍有飞行请求则卸载失败，日志 [DRAIN_TIMEOUT]
    force-drain-on-timeout: true
```

建议：

| 场景 | 建议 |
| --- | --- |
| 可接受打断长请求、优先腾出资源 | `force-drain-on-timeout: true` + 合适超时 |
| 长事务/不可丢在途 | `force-drain-on-timeout: false` + 更长 `force-cleanup-delay` |

调用侧在 ClassLoader 已清空时会得到确定性 `STATE_REJECTED`，避免难诊断 NPE。

### 共享 Spring 与卸载 SLA

- 默认与灵核**共享** `org.springframework.*`（runtime 父委派）。进程级静态缓存写入是模型代价，**不是**“运行期绝对静态隔离”。
- 卸载 SLA：**规范 undeploy 后 `LingClassLoader` 可被 GC（可证明）**；不要把“Spring 静态 Map 永不出现灵元类型键”当成验收标准。
- 观察：卸载完成事件 / 泄漏检测记录；`dev-mode` 下检测更积极。
- 若确认泄漏：失败路径可能在 `java.io.tmpdir` 写出 `ling-leak-*.hprof`。建议使用标准堆转储分析工具（如 Eclipse MAT、JProfiler 或 VisualVM）对 `.hprof` 堆转储文件进行分析（关注 `LingClassLoader` 的 GC Root 强引用路径）。

---

## 4. 服务演练场（Playground）

- 默认 **真实调用**（验接口友好）。
- 可在 UI 切到 **模拟**（仅治理链）。
- 生产务必配合 Dashboard token；勿把 Dashboard 暴露到不可信网络。

---

## 5. 状态与流量：各归其位

| 手段 | 用途 |
| --- | --- |
| **二维路由 / 权重 / 灰度** | **流量切分与停流**（不要用 RuntimeStatus 假想停流） |
| **权限 / `LING_ENABLE`** | 控制面启停授权 |
| **`INACTIVE`** | **事实**：无可用实例（聚合结果） |
| **卸载 → STOPPING → REMOVED** | 彻底下线、回收资源 |

不要用 RuntimeStatus 表达切流/停流；切流只改路由权重。

---

## 6. 构建与运行时矩阵

- 主验证路径：Spring Boot 2.7 + JDK 8（默认 profile `spring-boot2`）
- 支持线：`-Pspring-boot3` + JDK 17
- 结构（禁止反射探测 Servlet）：
  - Runtime：公共 `lingframe-spring-boot-starter` + 类型化 `lingframe-spring-boot2-starter` / `lingframe-spring-boot3-starter`
  - Dashboard：单 GAV + `src/java-javax` / `src/java-jakarta`（及对应 test），由 `build-helper` 按 profile 追加
- 切换矩阵务必 `clean`（SB3 的 class 在 JDK 8 上会直接失败）
- 贡献者细则：[DEVELOPMENT_MANUAL.md](../../DEVELOPMENT_MANUAL.md) 第 5.2 节
- 0.4 交付：**控制面 + 路由升维 + 正确性收口**；配置与边界见本文清单

---

## 7. 最小检查清单

- [ ] `dev-mode: false`
- [ ] Dashboard `allow-weak: false` + 强 token
- [ ] 按需打开 `ling-core-governance.enabled` / `check-permissions`
- [ ] `security.strict-mode: true`（除非有可审计豁免）
- [ ] 明确 `force-drain-on-timeout` 与超时时间（默认 true 会在 drain 超时后强制 tearDown）
- [ ] Shared API 变更走重启与版本包策略
- [ ] 不把示例 `123456` / 全开 dev 旁路原样上线
- [ ] 灵元访问 DB **只走注入的 DataSource Bean**，禁止灵元内 `DriverManager` / 私有池绕开治理

---

## 8. 可复制的生产 profile 片段

```yaml
# application-prod.yaml（示例，按环境改 token）
lingframe:
  enabled: true
  dev-mode: false
  ling-core-governance:
    enabled: true
    check-permissions: true
  security:
    strict-mode: true
  runtime:
    force-cleanup-delay: 60s
    force-drain-on-timeout: true   # 长事务不可打断时改为 false
  dashboard:
    enabled: true
    access-token:
      enabled: true
      token: "<rotate-me-strong-token>"
      allow-weak: false
```

本地示例可继续 `dev-mode: true` + `allow-weak: true`；**激活 `prod` profile 时不要沿用示例 token**。

---

## 9. 存储治理边界与表级语义

### 9.1 哪些路径会被代理（必须诚实）

| 路径 | 是否治理 | 机制 |
| --- | --- | --- |
| 灵核 / 灵元 Spring 容器中的 **`DataSource` Bean** | **是（主路径）** | `DataSourceWrapperProcessor` → `LingDataSourceProxy` → `LingConnectionProxy` |
| 灵元 `spring.datasource.*` 经 `LingDataSourceRegistrar` 注册为 Bean | **是**（成为 Bean 且被 BPP 包装后） | 同上 |
| 灵元内 `DriverManager.getConnection(...)` | **否** | 无 Bean 生命周期钩子 |
| 手搓连接池 / 字段里直接持有的非 Bean `DataSource` | **否** | 不进入包装器 |
| 代理上的 `getConnection(user, pass)` | **拦截**（仅代理路径） | 禁止任意凭据；直连驱动仍可能绕开 |

这是**组织型**治理（注入 + 代理），**不是** JVM 沙箱。生产约定：灵元**只**通过注入的 DataSource 访问库。

### 9.2 表级多条件（AND）

在代理路径生效且配置了表级权限/审计/限流时：

- 治理点之间是严格 **AND**：任一拦截则 SQL 阻断。
- 排障结合追踪 ID / 治理日志；先权限、再限流。

### 9.3 可选加固（非 0.4 必做）

| 方案 | 作用 | 代价 |
| --- | --- | --- |
| 规范 + Code Review / ArchUnit | 禁止灵元依赖 `DriverManager` 等 | 低，推荐 |
| 加载期扫描扩展（禁止 DriverManager 调用） | 安装时拒包 | 中，误杀风险 |
| Java Agent / 字节码插桩 | 运行时强制 | 高，复杂度大 |
| 仅开放受管 DataSource 给灵元 | 架构上不给直连入口 | 中，要改装配约定 |
