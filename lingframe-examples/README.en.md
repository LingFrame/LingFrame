# LingFrame Examples

Examples are organized in **two tracks**.

## Reverse selection table: what you want to do → which example to read

| You want to | Read this example |
| --- | --- |
| Shortest path to run a Ling | `lingcore-app` + `ling-user` / `ling-order` |
| Adopt LingFrame without touching a line of the legacy system | `saas-mall` (LingCore = `ling-mall` reused as-is as the base) |
| Override a legacy impl for strangle-migration | `saas-ling-oauth` (overrides `UserService.socialLogin`, adds SaaS multi-tenant governance) |
| Extend a legacy impl with extra governance | `saas-ling-seckill` (extends `SeckillService`, adds tenant-level quota pre-check) |
| Add a capability the legacy system lacks | `saas-ling-inventory` (TTL inventory hold; LingCore has no such contract → sole provider) |
| Canary release demo | `ling-order-canary` / `ling-user-canary` (dual versions + weight-based traffic shift) |
| Ling delegates to LingCore IService | `saas-ling-oauth` + `lingframe-infra-mybatis-plus` (`DelegatingIServiceSupport` removes IService boilerplate) |
| Cross-Ling invocation | `lingcore-app` where `ling-order` calls `ling-user` via `@LingReference` |

Fit + shortest run: root [README.md](../README.md).  
Public docs map: [docs/en/README.md](../docs/en/README.md).

## 1. Getting started (how to build and use)

| Module | Role |
| --- | --- |
| `lingframe-example-lingcore-app` | LingCore app + Dashboard; loads sample lings |
| `lingframe-example-ling-user` / `-canary` | User ling (+ canary) |
| `lingframe-example-ling-order` / `-canary` | Order ling (+ canary) |
| `lingframe-example-order-api` | Shared API contracts |
| `lingframe-example-ling-native` | Non-Spring ling entry |

Shortest path: [Quick start](../docs/en/quick-start.md), or `docs/en/getting-started.md`.

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am package -DskipTests
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

- App: `http://localhost:8888`
- Dashboard: `http://localhost:8888/dashboard.html`

## 2. Legacy system: simple adoption and gradual migration

| Module | Role |
| --- | --- |
| `lingframe-example-ling-mall` | Monolith mall base (“existing system”) |
| `lingframe-example-saas-mall` | Gradual split: LingCore reuses mall + business lings (oauth / refund / seckill) |

Details: `lingframe-example-saas-mall/README.md`

## 3. Config notes

- Getting-started defaults favor local use (`dev-mode`, sample token) — see each module’s `application.yaml`
- Production-oriented fragment: `lingframe-example-lingcore-app/src/main/resources/application-prod.yaml.example`
- Full checklist: `docs/en/production-hardening.md`
