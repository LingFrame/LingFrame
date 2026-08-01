# 灵珑示例（lingframe-examples）

示例分两条路径，按目标选入口即可。

## 反向选型表：你想做什么 → 看哪个示例

| 你想做 | 看这个示例 |
| --- | --- |
| 最短路径跑通一个灵元 | `lingcore-app` + `ling-user` / `ling-order` |
| 老系统不改一行接入灵珑 | `saas-mall`（灵核 = `ling-mall` 原封不动当底座） |
| 灵元覆盖老实现做绞杀迁移 | `saas-ling-oauth`（覆盖 `UserService.socialLogin`，叠加 SaaS 多租户治理） |
| 灵元拓展老实现加治理 | `saas-ling-seckill`（拓展 `SeckillService`，叠加租户级配额预检） |
| 灵元新增老系统没有的能力 | `saas-ling-inventory`（带 TTL 库存预占，灵核无此契约即唯一 provider） |
| 灰度发布演示 | `ling-order-canary` / `ling-user-canary`（双版本 + 权重切流） |
| 灵元 delegate 灵核 IService | `saas-ling-oauth` + `lingframe-infra-mybatis-plus`（`DelegatingIServiceSupport` 消 IService 桩代码） |
| 跨灵元调用 | `lingcore-app` 里 `ling-order` 经 `@LingReference` 调 `ling-user` |

判用与最短跑通：仓库根 [README.md](../../README.md)。  
公开文档地图：[docs/zh-CN/README.md](../../docs/zh-CN/README.md)。

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

## 2. 老系统：最简接入与渐进改造

| 模块 | 作用 |
| --- | --- |
| `lingframe-example-ling-mall` | 单体商城底座（可当作“既有系统”） |
| `lingframe-example-saas-mall` | 在 mall 上渐进拆成灵核 + 多业务灵元（oauth / seckill / inventory） |

说明与契约路由对照见：

`lingframe-example-saas-mall/README.md`

## 3. 配置提示

- 入门示例默认偏本地（如 `dev-mode`、示例 token），见各模块 `application.yaml`
- 生产姿态对照：`lingframe-example-lingcore-app/src/main/resources/application-prod.yaml.example`
- 更完整的配置说明：`docs/zh-CN/production-hardening.md`
