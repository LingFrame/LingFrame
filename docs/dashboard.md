# Dashboard: Visual Governance Center

LingFrame Dashboard is an optional advanced feature that provides a visual interface for plugin management and governance.

## Features Overview

| Feature | Description |
| ------- | ----------- |
| **Module Management** | List, Detail, Install, Uninstall, Hot Swap |
| **Status Control** | Start, Stop, Activate Modules |
| **Permission Governance** | Dynamically adjust module resource permissions (DB/Cache Read/Write) |
| **Canary Deployment** | Configure canary traffic percentage and version |
| **Traffic Statistics** | View call count, success rate, latency |
| **Simulation Testing** | Resource Access Simulation, IPC Simulation, Stress Testing |
| **Log Stream** | Real-time module log viewing (SSE) |

## Integration Steps

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.lingframe</groupId>
    <artifactId>lingframe-dashboard</artifactId>
    <version>${lingframe.version}</version>
</dependency>
```

### 2. Enable Dashboard

```yaml
lingframe:
  dashboard:
    enabled: true
```

![LingFrame Dashboard Example](./images/dashboard.png)
*Figure: Plugin Management Panel, showing real-time status, canary traffic, and audit logs.*

## API Endpoints

Once Dashboard is enabled, the following REST APIs are available:

### Module Management

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET    | `/lingframe/dashboard/plugins` | Get list of all modules |
| GET    | `/lingframe/dashboard/plugins/{pluginId}` | Get module details |
| POST   | `/lingframe/dashboard/plugins/install` | Upload and install JAR |
| DELETE | `/lingframe/dashboard/plugins/uninstall/{pluginId}` | Uninstall module |
| POST   | `/lingframe/dashboard/plugins/{pluginId}/reload` | Hot Swap (Dev Mode) |
| POST   | `/lingframe/dashboard/plugins/{pluginId}/status` | Update module status |

### Canary Deployment

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| POST   | `/lingframe/dashboard/plugins/{pluginId}/canary` | Configure canary strategy |

Request Body Example:
```json
{
  "percent": 10,
  "canaryVersion": "2.0.0"
}
```

### Governance Rules

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET    | `/lingframe/dashboard/governance/rules` | Get all governance rules |
| GET    | `/lingframe/dashboard/governance/{pluginId}` | Get module governance policy |
| POST   | `/lingframe/dashboard/governance/patch/{pluginId}` | Update governance policy |
| POST   | `/lingframe/dashboard/governance/{pluginId}/permissions` | Update resource permissions |

### Traffic Statistics

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET    | `/lingframe/dashboard/plugins/{pluginId}/stats` | Get traffic stats |
| POST   | `/lingframe/dashboard/plugins/{pluginId}/stats/reset` | Reset stats |

### Simulation Testing

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| POST   | `/lingframe/dashboard/simulate/plugins/{pluginId}/resource` | Simulate resource access |
| POST   | `/lingframe/dashboard/simulate/plugins/{pluginId}/ipc` | Simulate IPC call |
| POST   | `/lingframe/dashboard/simulate/plugins/{pluginId}/stress` | Stress test |

## Usage Examples

### View Plugin List

```bash
curl http://localhost:8888/lingframe/dashboard/plugins
```

### Hot Swap Plugin

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/plugins/order-plugin/reload
```

### Configure Canary Deployment

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/plugins/order-plugin/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "2.0.0"}'
```

## Considerations

1. **Dev/Test Environment Only**: In production, it is recommended to manage via configuration center.
2. **Security**: CORS allowed by default; configure authentication for production.
3. **Hot Swap**: Available only when `dev-mode: true`.
