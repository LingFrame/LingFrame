# Dashboard：治理控制面

LingFrame Dashboard 应该首先被理解为一个运行时治理控制面，而不是前端展示壳。

当前代码已经提供了一套可实际工作的后端治理入口，覆盖：

- 灵元生命周期操作
- 治理补丁与权限更新
- 灰度发布
- 模拟测试
- 指标与健康快照
- 基于监控事件的 SSE 推送

前端 UI 只是这些能力的一个消费者，不是能力本身。

Dashboard 的意义不只是“有一个后台页面”，而是它已经开始消费同一条运行时治理主链上的真实状态、真实事件和真实清理结果。

## 功能概览

| 功能 | 当前实现提供的能力 |
| :-- | :-- |
| **灵元管理** | 列表、详情、安装、卸载、按版本卸载、开发态热重载 |
| **运行时控制** | 通过 `ACTIVE`、`INACTIVE` 和移除流程调整运行时状态 |
| **治理补丁** | 查询与更新治理策略补丁 |
| **权限治理** | 更新 DB/Cache 权限与 IPC 能力授权 |
| **灰度发布** | 配置灰度比例与灰度版本路由 |
| **流量统计** | 查看总请求数、版本分流、活跃请求数与统计窗口起点 |
| **模拟测试** | 资源模拟、IPC 模拟、压力路由测试与 dev/prod 模式切换 |
| **指标与健康** | JVM 指标、单灵元健康快照、全量健康快照 |
| **事件流** | 基于监控事件的实时 SSE 订阅 |

## 服务层分工

当前 Dashboard 服务层已经做过一轮职责收束，不再把所有逻辑都压在一个胖服务中。

### 入口层

- [DashboardService.java](../../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardService.java)
  负责查询入口、委派与少量结果拼装。

### 治理与时间线

- [DashboardGovernanceSupport.java](../../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardGovernanceSupport.java)
  负责治理 patch 合并、权限同步、调用治理配置更新。
- [DashboardLifecycleEventStore.java](../../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardLifecycleEventStore.java)
  负责时间线事件存储与裁剪。

### 状态与生命周期操作

- [DashboardStatusCoordinator.java](../../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardStatusCoordinator.java)
  负责状态迁移、副作用与时间线写入。
- [DashboardLingSourceResolver.java](../../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardLingSourceResolver.java)
  负责热重载时的实例选择、源码定位、版本号生成和 reload 标记。
- [DashboardLingOperations.java](../../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardLingOperations.java)
  负责安装、卸载、按版本卸载和热重载的生命周期操作编排。
- [DashboardUninstallResultMapper.java](../../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardUninstallResultMapper.java)
  负责卸载结果 DTO 转换。

对应测试也已经补齐，保证这轮拆分不是只有结构变化而没有验证：

- [DashboardServiceTest.java](../../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardServiceTest.java)
- [DashboardGovernanceSupportTest.java](../../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardGovernanceSupportTest.java)
- [DashboardLifecycleEventStoreTest.java](../../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardLifecycleEventStoreTest.java)
- [DashboardStatusCoordinatorTest.java](../../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardStatusCoordinatorTest.java)
- [DashboardLingSourceResolverTest.java](../../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardLingSourceResolverTest.java)
- [DashboardLingOperationsTest.java](../../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardLingOperationsTest.java)
- [DashboardUninstallResultMapperTest.java](../../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardUninstallResultMapperTest.java)

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

Dashboard 后端控制面默认暴露在：

- REST：`/lingframe/dashboard/**`
- SSE：`/lingframe/dashboard/stream`

其中：

- 安装接口需要额外启用：`lingframe.dashboard.install-enabled=true`
- 热重载接口只在 `lingframe.dev-mode=true` 时可用

![LingFrame Dashboard 示例](./../images/dashboard.zh-CN.png)
*图示：Dashboard 可以是治理控制面的一个 UI 消费者。*

## API 端点

### 灵元管理

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/lings` | 获取所有灵元列表 |
| GET | `/lingframe/dashboard/lings/{lingId}` | 获取灵元详情 |
| POST | `/lingframe/dashboard/lings/install` | 上传并安装 JAR，前提是安装开关已启用 |
| DELETE | `/lingframe/dashboard/lings/uninstall/{lingId}` | 卸载整个灵元 |
| DELETE | `/lingframe/dashboard/lings/uninstall/{lingId}/{version}` | 卸载指定版本 |
| POST | `/lingframe/dashboard/lings/{lingId}/reload` | 开发态热重载 |
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
| GET | `/lingframe/dashboard/governance/{lingId}/invocation` | 获取灵元当前调用治理配置 |
| POST | `/lingframe/dashboard/governance/{lingId}/invocation` | 更新调用治理配置（超时、限流、并发） |
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
| GET | `/lingframe/dashboard/lings/governance/all` | 获取所有灵元治理信号快照 |
| GET | `/lingframe/dashboard/lings/timeline` | 获取生命周期时间线事件 |

### 模拟测试

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/resource` | 资源访问模拟 |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/ipc` | IPC 调用模拟 |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/stress` | 压力路由测试 |
| POST | `/lingframe/dashboard/simulate/config/mode` | 在 dev/prod 测试模式之间切换 |

### 事件流

| 方法 | 端点 | 说明 |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/stream` | 订阅 SSE 监控事件流 |

## 使用示例

### 查看灵元列表

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

### 开发态热重载灵元

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

1. Dashboard 是可选模块，只有在 `lingframe.dashboard.enabled=true` 时才会启用。
2. 安装接口默认关闭，需要显式设置 `lingframe.dashboard.install-enabled=true`。
3. 热重载能力只属于开发态，`lingframe.dev-mode=false` 时会被拒绝。
4. CORS 由集中式 `DashboardCorsFilter` 统一管控。当 access-token 认证已启用且未配置 `lingframe.dashboard.cors.allowed-origins` 时，仅允许同源请求。跨域部署场景需显式配置：
   ```yaml
   lingframe:
     dashboard:
       cors:
         allowed-origins:
           - "https://admin.example.com"
   ```
5. 真实后端 API 面才是文档应对齐的事实来源，UI 打包与前端壳层不是当前阶段重点。
6. Dashboard 更适合被理解为运行时治理的观察与操作入口，而不是独立于内核之外的另一个系统。
