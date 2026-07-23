# Dashboard: The Governance Control Surface

The LingFrame Dashboard should first be understood as a runtime governance control surface, not merely a frontend presentation shell.

The current codebase already provides a viable backend governance entry point covering:

- Ling lifecycle operations
- Governance patches and permission updates
- Canary releases 
- Simulation testing
- Metrics and health snapshots
- SSE streaming based on monitoring events

The frontend UI is just one consumer of these capabilities, not the capability itself.

The significance of the Dashboard is not just "having an admin page," but that it has started consuming real states, real events, and real cleanup outcomes straight from the shared runtime governance chain.

## Feature Overview

| Feature | Capabilities provided by current implementation |
| :-- | :-- |
| **Ling Management** | Lists, details, install, uninstall, uninstall by version, and hot-reload in dev mode |
| **Runtime Control** | Adjust runtime state through `ACTIVE`, `INACTIVE` and removal flows |
| **Governance Patches** | Query and update governance strategy patches |
| **Permission Governance** | Update DB/Cache permissions and IPC capability authorizations |
| **Canary Releases** | Configure canary ratios and canary version routing |
| **Traffic Statistics** | View total requests, version routing split, active requests, and statistic window start |
| **Simulation Testing** | Resource simulation, IPC simulation, stress test routing, and dev/prod mode toggling |
| **Metrics & Health** | JVM metrics, single-ling health snapshot, and holistic health snapshots |
| **Event Streams** | Real-time SSE subscriptions based on monitoring events |

## Service Layer Segregation

The current Dashboard Service layer has undergone a round of responsibility convergence, so that all logic is no longer collapsed into a single fat service.

### Entry Layer

- [DashboardService.java](../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardService.java)
  Responsible for query entries, delegation, and light result-assembly.

### Governance and Timeline

- [DashboardGovernanceSupport.java](../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardGovernanceSupport.java)
  Responsible for merging governance patches, syncing permissions, and updating invocation governance config.
- [DashboardLifecycleEventStore.java](../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardLifecycleEventStore.java)
  Responsible for storing and pruning timeline events.

### State and Lifecycle Operations

- [DashboardStatusCoordinator.java](../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardStatusCoordinator.java)
  Responsible for state transitions, side-effects, and timeline writing.
- [DashboardLingSourceResolver.java](../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardLingSourceResolver.java)
  Responsible for instance selection during hot-reload, source locating, version generation, and reload flagging.
- [DashboardLingOperations.java](../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardLingOperations.java)
  Responsible for lifecycle operation orchestration like install, uninstall, partial unload, and hot-reload.
- [DashboardUninstallResultMapper.java](../lingframe-dashboard/src/main/java/com/lingframe/dashboard/service/DashboardUninstallResultMapper.java)
  Responsible for mapping unload results to DTOs.

Corresponding tests have also been implemented, ensuring this round of factoring is not purely structural without verification:

- [DashboardServiceTest.java](../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardServiceTest.java)
- [DashboardGovernanceSupportTest.java](../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardGovernanceSupportTest.java)
- [DashboardLifecycleEventStoreTest.java](../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardLifecycleEventStoreTest.java)
- [DashboardStatusCoordinatorTest.java](../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardStatusCoordinatorTest.java)
- [DashboardLingSourceResolverTest.java](../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardLingSourceResolverTest.java)
- [DashboardLingOperationsTest.java](../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardLingOperationsTest.java)
- [DashboardUninstallResultMapperTest.java](../lingframe-dashboard/src/test/java/com/lingframe/dashboard/service/DashboardUninstallResultMapperTest.java)

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

### 3. Runtime Entries

The Dashboard backend control surface is exposed by default at:

- REST: `/lingframe/dashboard/**`
- SSE: `/lingframe/dashboard/stream`

Where:

- The install API needs extra enablement: `lingframe.dashboard.install-enabled=true`
- The hot-reload API is only available when `lingframe.dev-mode=true`

![LingFrame Dashboard Example](./images/dashboard.png)
*Fig: The Dashboard can act as a UI consumer for the governance control surface.*

## API Endpoints

### Ling Management

| Method | Endpoint | Description |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/lings` | Get a list of all lings |
| GET | `/lingframe/dashboard/lings/{lingId}` | Get ling details |
| POST | `/lingframe/dashboard/lings/install` | Upload and install a JAR (if install switch is enabled) |
| DELETE | `/lingframe/dashboard/lings/uninstall/{lingId}` | Uninstall the whole ling |
| DELETE | `/lingframe/dashboard/lings/uninstall/{lingId}/{version}` | Uninstall a specific version |
| POST | `/lingframe/dashboard/lings/{lingId}/reload` | Hot-reload in dev mode |
| POST | `/lingframe/dashboard/lings/{lingId}/status` | Update the ling's runtime state |

### Canary Releases

| Method | Endpoint | Description |
| :-- | :-- | :-- |
| POST | `/lingframe/dashboard/lings/{lingId}/canary` | Update canary ratios and target version |

Example body:

```json
{
  "percent": 10,
  "canaryVersion": "2.0.0"
}
```

### Governance Rules

| Method | Endpoint | Description |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/governance/rules` | Grab all governance patches |
| GET | `/lingframe/dashboard/governance/{lingId}` | Grab the governance strategy of a single ling |
| POST | `/lingframe/dashboard/governance/patch/{lingId}` | Push governance strategy updates |
| GET | `/lingframe/dashboard/governance/{lingId}/invocation` | Get current invocation governance config |
| POST | `/lingframe/dashboard/governance/{lingId}/invocation` | Update invocation governance config (timeout, rate limit, concurrency) |
| POST | `/lingframe/dashboard/governance/{lingId}/permissions` | Update resource permissions and IPC authorization |

### Traffic Statistics

| Method | Endpoint | Description |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/lings/{lingId}/stats` | Grab total requests, routing split, active requests, and window start |
| POST | `/lingframe/dashboard/lings/{lingId}/stats/reset` | Reset traffic statistics |

### Metrics & Health

| Method | Endpoint | Description |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/lings/metrics` | Grab JVM metric snapshots |
| GET | `/lingframe/dashboard/lings/{lingId}/health` | Grab health snapshot for a single ling |
| GET | `/lingframe/dashboard/lings/health/all` | Grab health snapshots across all lings |
| GET | `/lingframe/dashboard/lings/governance/all` | Grab governance signal snapshots across all lings |
| GET | `/lingframe/dashboard/lings/timeline` | Grab lifecycle timeline events |

### Simulation Testing

| Method | Endpoint | Description |
| :-- | :-- | :-- |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/resource` | Simulate resource access |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/ipc` | Simulate IPC invocation |
| POST | `/lingframe/dashboard/simulate/lings/{lingId}/stress` | Simulate stress test routing |
| POST | `/lingframe/dashboard/simulate/config/mode` | Toggle between dev/prod testing modes |

### Event Streams

| Method | Endpoint | Description |
| :-- | :-- | :-- |
| GET | `/lingframe/dashboard/stream` | Subscribe to SSE monitoring event streams |

## Usage Examples

### View Ling List

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

### Hot-Reload a Ling in Dev Mode

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/reload
```

### Configure Canary Release

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/lings/order-ling/canary \
  -H "Content-Type: application/json" \
  -d '{"percent": 20, "canaryVersion": "2.0.0"}'
```

## Need to Know

1. The Dashboard is an optional module, enabled only when `lingframe.dashboard.enabled=true`.
2. The installation endpoint is turned off by default, requiring an explicit `lingframe.dashboard.install-enabled=true`.
3. Hot-reload capabilities purely belong to dev mode and will be blocked if `lingframe.dev-mode=false`.
4. CORS is enforced by a centralized `DashboardCorsFilter`. When access-token auth is enabled and no explicit `lingframe.dashboard.cors.allowed-origins` are configured, only same-origin requests are permitted. For cross-origin deployments, configure allowed origins explicitly:
   ```yaml
   lingframe:
     dashboard:
       cors:
         allowed-origins:
           - "https://admin.example.com"
   ```
5. The true backend API surface handles the source of facts that documents align with; UI bundling and shells are not the current focal point.
6. The Dashboard is better understood as an observing and operating inlet for runtime governance, not an independent system walled off from the kernel.
