# 生产硬化配置清单

面向把灵珑装进**真实生产灵核**时的最小硬化建议。  
不替代安全审计；只列可立即落地的配置与运维动作。

> 术语：用「灵核 / 灵元」，不要写「宿主 / 插件」。

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

---

## 4. 服务演练场（Playground）

- 默认 **真实调用**（验接口友好）。
- 可在 UI 切到 **模拟**（仅治理链）。
- 生产务必配合 Dashboard token；勿把 Dashboard 暴露到不可信网络。

---

## 5. 状态操作语义

| 操作 | 含义 |
| --- | --- |
| **软停**（Runtime → `INACTIVE`） | 停新流量 / 收权；**实例仍在进程内** |
| **卸载 / REMOVED** | 排空 + tearDown + 资源清理 |

软停 API：`POST /lingframe/dashboard/lings/{lingId}/soft-stop`（body 可选 `{"version":"..."}`）。  
勿把软停当成「已干净卸载」。

---

## 6. 构建与运行时矩阵

- 主验证路径：Spring Boot 2.7 + JDK 8（默认 profile）
- 双栈：`-Pspring-boot3` + JDK 17
- 0.4 定位：**设计债收敛后的候选内核**，不是「无需再硬化的生产认证」

---

## 7. 最小检查清单

- [ ] `dev-mode: false`
- [ ] Dashboard `allow-weak: false` + 强 token
- [ ] 按需打开 `ling-core-governance.enabled` / `check-permissions`
- [ ] `security.strict-mode: true`（除非有可审计豁免）
- [ ] 明确 `force-drain-on-timeout` 与超时时间
- [ ] Shared API 变更走重启与版本包策略
- [ ] 不把示例 `123456` / 全开 dev 旁路原样上线

---

## 8. 数据库表级治理语义（AND 逻辑）

在生产环境中如果启用了 `ling-core-governance` 并代理了数据源，以下是数据治理的关键事实：

- **表级多条件治理（AND 语义）**：当同时对表或操作配置了权限、审计、限流时，这些治理逻辑是严格的 **AND 关系**。
  - **任何一个**拦截点（如权限拒绝、或超出限流、或审计拦截）触发，SQL 都会被阻断，并抛出 `LingGovernanceException` / `LingSecurityException`。
- **运维提示**：
  - 排查 SQL 被拒时，必须结合 `X-Ling-Trace-Id` 查看日志中的 `[Ling-Governance]` 标签。
  - 先确认权限是否匹配，再确认是否撞了表级或全局限流。
  - 如果日志中出现 `Unmanaged DataSource` 警告，意味着该流量绕过了所有治理，需检查数据源注册代码。
