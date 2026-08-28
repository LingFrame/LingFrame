# LingFrame Examples

Examples are organized in **two tracks**.

## Reverse selection table: what you want to do → which example to read

| You want to | Read this example |
| --- | --- |
| Shortest path to run a Ling | `lingcore-app` + `ling-user` / `ling-order` |
| Canary release demo | `ling-order-canary` / `ling-user-canary` (dual versions + weight-based traffic shift) |
| Cross-Ling invocation | `lingcore-app` where `ling-order` calls `ling-user` via `@LingReference` |
| Real-world legacy monolith migration | **LingFrame-RuoYi** (Companion project: Best practices for modularizing a production monolith) |

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

## 2. Real-world Legacy Migration Example

The full reference for non-disruptive, gradual strangle-migration of a legacy monolith (e.g. RuoYi enterprise management system) is maintained in our companion repository:

- **Companion project**: `LingFrame-RuoYi` (independent repository alongside LingFrame)
- **Key scenarios**: LingCore as untouched base, business lings overriding legacy implementations, contract hot-plugging, and dual-provider traffic shifting.

## 3. Config notes

- Getting-started defaults favor local use (`dev-mode`, sample token) — see each module’s `application.yaml`
- Production-oriented fragment: `lingframe-example-lingcore-app/src/main/resources/application-prod.yaml.example`
- Full checklist: `docs/en/production-hardening.md`
