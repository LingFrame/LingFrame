# Dashboard: Visual Governance Center

LingFrame Dashboard is an optional advanced feature that provides a visual interface for ling management and governance.

## Features Overview

| Feature | Description |
| ------- | ----------- |
| **Unit Management** | List, Detail, Install, Uninstall, Hot Swap |
| **Status Control** | Start, Stop, Activate Units |
| **Permission Governance** | Dynamically adjust unit resource permissions (DB/Cache Read/Write) |
| **Canary Deployment** | Configure canary traffic percentage and version |
| **Traffic Statistics** | View call count, success rate, latency |
| **Simulation Testing** | Resource Access Simulation, IPC Simulation, Stress Testing |
| **Log Stream** | Real-time unit log viewing (SSE) |

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
*Figure: ling Management Panel, showing real-time status, canary traffic, and audit logs.*

## API Endpoints

Once Dashboard is enabled, the following REST APIs are available:

### Unit Management

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET    | `/lingframe/dashboard/lings` | Get list of all units |
| GET    | `/lingframe/dashboard/lings/{lingId}` | Get unit details |
| POST   | `/lingframe/dashboard/lings/install` | Upload and install JAR |
| DELETE | `/lingframe/dashboard/lings/uninstall/{lingId}` | Uninstall unit |
| POST   | `/lingframe/dashboard/lings/{lingId}/reload` | Hot Swap (Dev Mode) |
| POST   | `/lingframe/dashboard/lings/{lingId}/status` | Update unit status |

### Canary Deployment

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| POST   | `/lingframe/dashboard/lings/{lingId}/canary` | Configure canary strategy |

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
| GET    | `/lingframe/dashboard/governance/{lingId}` | Get unit governance policy |
| POST   | `/lingframe/dashboard/governance/patch/{lingId}` | Update governance policy |
| POST   | `/lingframe/dashboard/governance/{lingId}/permissions` | Update resource permissions |

### Traffic Statistics

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET    | `/lingframe/dashboard/lings/{lingId}/stats` | Get traffic stats |
| POST   | `/lingframe/dashboard/lings/{lingId}/stats/reset` | Reset stats |

### Simulation Testing

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| POST   | `/lingframe/dashboard/simulate/lings/{lingId}/resource` | Simulate resource access |
| POST   | `/lingframe/dashboard/simulate/lings/{lingId}/ipc` | Simulate IPC call |
| POST   | `/lingframe/dashboard/simulate/lings/{lingId}/stress` | Stress test |

## Usage Examples

### View ling List

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

### Hot Swap ling

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/reload
```

### Configure Canary Deployment

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "2.0.0"}'
```

## Considerations

1. **Dev/Test Environment Only**: In production, it is recommended to manage via configuration center.
2. **Security**: CORS allowed by default; configure authentication for production.
3. **Hot Swap**: Available only when `dev-mode: true`.
