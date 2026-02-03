# Dashboard 可视化治理中心

LingFrame Dashboard 是一个可选的高阶功能，提供可视化的插件管理和治理界面。

## 功能概览

| 功能 | 说明 |
|------|------|
| **模块管理** | 列表、详情、安装、卸载、热重载 |
| **状态控制** | 启动、停止、激活模块 |
| **权限治理** | 动态调整模块资源权限（DB/Cache 读写） |
| **灰度发布** | 配置灰度流量比例和版本 |
| **流量统计** | 查看调用次数、成功率、耗时 |
| **模拟测试** | 资源访问模拟、IPC 模拟、压力测试 |
| **日志流** | 实时查看模块日志（SSE） |

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
*图示：插件管理面板，展示实时状态、灰度流量和审计日志。*

## API 端点

Dashboard 启用后，以下 REST API 可用：

### 模块管理

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/lingframe/dashboard/plugins` | 获取所有模块列表 |
| GET | `/lingframe/dashboard/plugins/{pluginId}` | 获取模块详情 |
| POST | `/lingframe/dashboard/plugins/install` | 上传安装 JAR 包 |
| DELETE | `/lingframe/dashboard/plugins/uninstall/{pluginId}` | 卸载模块 |
| POST | `/lingframe/dashboard/plugins/{pluginId}/reload` | 热重载（开发模式） |
| POST | `/lingframe/dashboard/plugins/{pluginId}/status` | 更新模块状态 |

### 灰度发布

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/lingframe/dashboard/plugins/{pluginId}/canary` | 配置灰度策略 |

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
| GET | `/lingframe/dashboard/governance/{pluginId}` | 获取模块治理策略 |
| POST | `/lingframe/dashboard/governance/patch/{pluginId}` | 更新治理策略 |
| POST | `/lingframe/dashboard/governance/{pluginId}/permissions` | 更新资源权限 |

### 流量统计

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/lingframe/dashboard/plugins/{pluginId}/stats` | 获取流量统计 |
| POST | `/lingframe/dashboard/plugins/{pluginId}/stats/reset` | 重置统计 |

### 模拟测试

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/lingframe/dashboard/simulate/plugins/{pluginId}/resource` | 模拟资源访问 |
| POST | `/lingframe/dashboard/simulate/plugins/{pluginId}/ipc` | 模拟 IPC 调用 |
| POST | `/lingframe/dashboard/simulate/plugins/{pluginId}/stress` | 压力测试 |

## 使用示例

### 查看插件列表

```bash
curl http://localhost:8888/lingframe/dashboard/plugins
```

### 热重载插件

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/plugins/order-plugin/reload
```

### 配置灰度发布

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/plugins/order-plugin/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "2.0.0"}'
```

## 注意事项

1. **仅用于开发/测试环境**：生产环境建议通过配置中心管理
2. **安全考虑**：默认允许跨域，生产环境需配置认证
3. **热重载**：仅在 `dev-mode: true` 时可用
