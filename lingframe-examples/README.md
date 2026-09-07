# 灵珑示例（lingframe-examples）

示例分两条路径，按目标选入口即可。

## 反向选型表：你想做什么 → 看哪个示例

| 你想做 | 看这个示例 |
| --- | --- |
| 最短路径跑通一个灵元 | `lingcore-app` + `ling-user` / `ling-order` |
| 灰度发布演示 | `ling-order-canary` / `ling-user-canary`（双版本 + 权重切流） |
| 跨灵元调用 | `lingcore-app` 里 `ling-order` 经 `@LingReference` 调 `ling-user` |
| 真实既有单体系统渐进改造 | **LingFrame-RuoYi**（独立项目：真实第三方单体架构灵元化改造最佳实践） |

判用与最短跑通：仓库根 [README.md](../README.md)。  
公开文档地图：[docs/zh-CN/README.md](../docs/zh-CN/README.md)。

> **示例下线说明**：原 `lingframe-example-ling-mall` 与 `lingframe-example-saas-mall` 已下线，
> 托管数据源与事务传播示例请参考 [docs/zh-CN/managed-datasource-and-transaction.md](../docs/zh-CN/managed-datasource-and-transaction.md)，
> 非 Spring 灵元入口参考可用 `lingframe-example-ling-native`。

## 1. 入门：怎么开发、怎么用

| 模块 | 作用 |
| --- | --- |
| `lingframe-example-lingcore-app` | 灵核应用 + Dashboard，默认加载示例灵元 |
| `lingframe-example-ling-user` / `-canary` | 用户灵元（含灰度） |
| `lingframe-example-ling-order` / `-canary` | 订单灵元（含灰度） |
| `lingframe-example-order-api` | 共享契约（Shared API） |
| `lingframe-example-ling-native` | 非 Spring 灵元入口参考 |

最短跑通：[最短上手](../docs/zh-CN/quick-start.md)，或 `docs/zh-CN/getting-started.md`。

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am package -DskipTests
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

- 应用：`http://localhost:8888`
- Dashboard：`http://localhost:8888/dashboard.html`

## 2. 既有系统改造真实范例

真实单体（如 RuoYi 等典型后台管理与业务系统）的不停机渐进拆分与灵元化改造示范，由同级独立开源项目专门承载：

- **独立项目**：`LingFrame-RuoYi`（与灵珑框架同级的独立代码仓库）
- **核心场景**：灵核原封不动当底座、业务灵元覆盖老实现做绞杀迁移、按契约热插拔与双 Provider 权重切流。

## 3. 配置提示

- 入门示例默认偏本地（如 `dev-mode`、示例 token），见各模块 `application.yaml`
- 生产姿态对照：`lingframe-example-lingcore-app/src/main/resources/application-prod.yaml.example`
- 更完整的配置说明：`docs/zh-CN/production-hardening.md`
