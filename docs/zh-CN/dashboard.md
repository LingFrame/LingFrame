# Dashboard 可视化治理中心

LingFrame Dashboard 是一个可选的高阶功能，提供可视化的单元管理和治理界面。

## 功能概览

| 功能 | 说明 |
|------|------|
| **单元管理** | 列表、详情、安装、卸载、热重载 |
| **状态控制** | 启动、停止、激活单元 |
| **权限治理** | 动态调整单元资源权限（DB/Cache 读写） |
| **灰度发布** | 配置灰度流量比例和版本 |
| **流量统计** | 查看调用次数、成功率、耗时 |
| **模拟测试** | 资源访问模拟、IPC 模拟、压力测试 |
| **日志流** | 实时查看单元日志（SSE） |

## 集成步骤

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

![LingFrame Dashboard 示例](./../images/dashboard.zh-CN.png)
*图示：单元管理面板，展示实时状态、灰度流量和审计日志。*

## API 端点

Dashboard 启用后，以下 REST API 可用：

### 单元管理

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/lingframe/dashboard/lings` | 获取所有单元列表 |
| GET | `/lingframe/dashboard/lings/{lingId}` | 获取单元详情 |
| POST | `/lingframe/dashboard/lings/install` | 上传安装 JAR 包 |
| DELETE | `/lingframe/dashboard/lings/uninstall/{lingId}` | 卸载单元 |
| POST | `/lingframe/dashboard/lings/{lingId}/reload` | 热重载（开发模式） |
| POST | `/lingframe/dashboard/lings/{lingId}/status` | 更新单元状态 |

### 灰度发布

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/lingframe/dashboard/lings/{lingId}/canary` | 配置灰度策略 |

请求体示例：
```json
{
  "percent": 10,
  "canaryVersion": "2.0.0"
}
```

### 治理规则

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/lingframe/dashboard/governance/rules` | 获取所有治理规则 |
| GET | `/lingframe/dashboard/governance/{lingId}` | 获取单元治理策略 |
| POST | `/lingframe/dashboard/governance/patch/{lingId}` | 更新治理策略 |
| POST | `/lingframe/dashboard/governance/{lingId}/permissions` | 更新资源权限 |

### 流量统计

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/lingframe/dashboard/lings/{lingId}/stats` | 获取流量统计 |
| POST | `/lingframe/dashboard/lings/{lingId}/stats/reset` | 重置统计 |

### 模拟测试

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/resource` | 模拟资源访问 |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/ipc` | 模拟 IPC 调用 |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/stress` | 压力测试 |

## 使用示例

### 查看单元列表

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

### 热重载单元

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

1. **仅用于开发/测试环境**：生产环境建议通过配置中心管理
2. **安全考虑**：默认允许跨域，生产环境需配置认证
3. **热重载**：仅在 `dev-mode: true` 时可用
