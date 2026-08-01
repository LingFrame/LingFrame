# 灵珑示例（lingframe-examples）

示例分两条路径，按目标选入口即可。

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

最短跑通：仓库根目录 `QUICK_START.md`，或 `docs/zh-CN/getting-started.md`。

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
