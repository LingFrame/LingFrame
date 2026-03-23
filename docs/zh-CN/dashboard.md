# Dashboard 治理控制面

在 `0.3.0` 里，LingFrame Dashboard 更应该被理解为**运行时治理控制面**，而不是一个前端展示壳。

当前已交付代码的重点，是一组真实可用的后端治理接口：灵元生命周期操作、治理补丁、模拟、指标、健康快照，以及基于内核事件的 SSE 流。前端 UI 只是这些能力的一个消费者。

它之所以重要，不只是因为“有一个后台可以点按钮”，  
而是因为它已经开始接入同一条运行时主链上的真实状态、真实事件和真实清理结果。

## 功能概览

| 功能 | 当前实现提供的能力 |
| :-- | :-- |
| **灵元管理** | 列表、详情、安装、卸载、按版本卸载、开发态重载 |
| **运行时控制** | 通过 `ACTIVE`、`INACTIVE` 与 removal 流程调整运行时状态 |
| **治理补丁** | 查询与更新治理策略补丁 |
| **权限治理** | 更新 DB/Cache 权限与 IPC 能力授权 |
| **灰度发布** | 配置灰度比例与灰度版本路由 |
| **流量统计** | 查看请求总量、版本分流、活跃请求数与统计窗口起点 |
| **模拟测试** | 资源模拟、IPC 模拟、压力路由测试、dev/prod 模式切换 |
| **指标与健康** | JVM 指标、单灵元健康快照、全量健康快照 |
| **事件流** | 基于监控事件的实时 SSE 订阅 |

从项目识别度来说，Dashboard 的价值也不只是“展示治理能力”，  
而是把长期运行中的治理证据、卸载过程与运行时反馈真正汇聚到同一个控制面。

## 接入步骤

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-dashboard</artifactId>
    <version>${lingframe.version}</version>
</dependency>
```

### 2. 启用 Dashboard

```yaml
lingframe:
  dashboard:
    enabled: true
```

### 3. 运行时入口

后端控制面启用后，暴露在：

- REST：`/lingframe/dashboard/**`
- SSE：`/lingframe/dashboard/stream`

其中：

- 安装接口还需要额外开启 `lingframe.dashboard.install-enabled=true`
- 重载接口只在 `lingframe.dev-mode=true` 时可用

![LingFrame Dashboard 示例](./../images/dashboard.zh-CN.0.3.0.png)
*图示：Dashboard 可以是治理控制面的一个 UI 消费者。*

## API 端点

### 灵元管理

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/lings` | 获取所有灵元列表 |
| GET | `/lingframe/dashboard/lings/{lingId}` | 获取灵元详情 |
| POST | `/lingframe/dashboard/lings/install` | 上传并安装 JAR，前提是安装开关已开启 |
| DELETE | `/lingframe/dashboard/lings/uninstall/{lingId}` | 卸载整个灵元 |
| DELETE | `/lingframe/dashboard/lings/uninstall/{lingId}/{version}` | 卸载指定版本 |
| POST | `/lingframe/dashboard/lings/{lingId}/reload` | 开发态重载 |
| POST | `/lingframe/dashboard/lings/{lingId}/status` | 更新灵元运行时状态 |

### 灰度发布

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| POST | `/lingframe/dashboard/lings/{lingId}/canary` | 更新灰度比例与灰度版本 |

请求体示例：

```json
{
  "percent": 10,
  "canaryVersion": "2.0.0"
}
```

### 治理规则

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/governance/rules` | 获取全部治理补丁 |
| GET | `/lingframe/dashboard/governance/{lingId}` | 获取单个灵元治理策略 |
| POST | `/lingframe/dashboard/governance/patch/{lingId}` | 更新治理策略补丁 |
| POST | `/lingframe/dashboard/governance/{lingId}/permissions` | 更新资源权限与 IPC 授权 |

### 流量统计

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/lings/{lingId}/stats` | 获取请求总量、版本分流、活跃请求数与窗口起点 |
| POST | `/lingframe/dashboard/lings/{lingId}/stats/reset` | 重置流量统计 |

### 指标与健康

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/lings/metrics` | 获取 JVM 指标快照 |
| GET | `/lingframe/dashboard/lings/{lingId}/health` | 获取单个灵元健康快照 |
| GET | `/lingframe/dashboard/lings/health/all` | 获取所有灵元健康快照 |

### 模拟测试

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/resource` | 资源访问模拟 |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/ipc` | IPC 调用模拟 |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/stress` | 压力路由测试 |
| POST | `/lingframe/dashboard/simulate/config/mode` | 在 dev/prod 测试模式间切换 |

### 事件流

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/stream` | 订阅 SSE 监控事件流 |

## 使用示例

### 查看灵元列表

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

### 开发态重载灵元

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/reload
```

### 配置灰度发布

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "2.0.0"}'
```

## 注意事项

1. Dashboard 是可选模块，只有在 `lingframe.dashboard.enabled=true` 时才启用。
2. 安装接口默认关闭，需要显式设置 `lingframe.dashboard.install-enabled=true`。
3. 重载能力只属于开发态，`lingframe.dev-mode=false` 时会被拒绝。
4. 当前实现默认开放 CORS，生产环境应在 Dashboard 前增加鉴权与访问控制。
5. 对 `0.3.0` 来说，真实后端 API 面才是文档应对齐的事实来源，UI 打包与前端壳层不是本阶段重点。
6. Dashboard 更适合被理解为运行时治理的观察与操作入口，而不是独立于内核之外的另一个系统。
