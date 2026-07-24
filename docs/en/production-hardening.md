# Production Hardening Checklist

Minimal hardening guidance when installing LingFrame into a real **LingCore** process.  
This is not a substitute for a security review — only immediately actionable config.

> Terms: use **LingCore / Ling**, not Host / Plugin.

---

## 1. Dashboard access control

```yaml
lingframe:
  dashboard:
    enabled: true
    access-token:
      enabled: true
      token: "<strong-unique-token>"
      # Production must be false: weak tokens fail startup
      allow-weak: false
```

- Example apps may keep `token: "123456"` with `allow-weak: true` — **do not** ship that to production.
- Token is accepted only via header: `X-Access-Token`.

---

## 2. LingCore governance posture

Defaults favor local/historical assemblies. **Turn these on explicitly for production**:

```yaml
lingframe:
  dev-mode: false

  ling-core-governance:
    enabled: true
    check-permissions: true
    # govern-internal-calls: false

  security:
    strict-mode: true
```

Notes:

- When not in dev mode and governance is off, `LingFrameConfig.init` logs a **WARN**.
- Dangerous-API scanning is **load-time** signaling, **not** a full JVM sandbox. See [Shared API guidelines](shared-api-guidelines.md).

---

## 3. Unload / drain

```yaml
lingframe:
  runtime:
    force-cleanup-delay: 30s
    # true (default): after timeout, force tearDown; may interrupt in-flight work; logs [FORCE_DRAIN]
    # false: if still busy after timeout, unload fails; logs [DRAIN_TIMEOUT]
    force-drain-on-timeout: true
```

| Scenario | Suggestion |
| --- | --- |
| Prefer reclaiming resources; short interrupts OK | `force-drain-on-timeout: true` + suitable timeout |
| Long transactions must not be cut | `force-drain-on-timeout: false` + longer delay |

Invokers return a deterministic `STATE_REJECTED` when the instance classloader is already gone.

### Shared Spring and unload SLA

- By default the process **shares** `org.springframework.*` (runtime parent-delegate). Process-level static-cache writes are a model cost, **not** “absolute static isolation at runtime”.
- Unload SLA: after a **proper undeploy**, `LingClassLoader` is **GC-collectable (provable)**; do not treat “Spring static maps never hold ling type keys” as the acceptance criterion.
- Observe unload/leak-detection events (`dev-mode` is more aggressive).
- On confirmed leaks, failed paths may write `ling-leak-*.hprof` under `java.io.tmpdir`. Use standard heap dump analysis tools (e.g. Eclipse MAT, JProfiler, or VisualVM) to analyze strong GC Root references holding the `LingClassLoader`.

---

## 4. Service Playground

- Default is **real invoke** (API verification).
- UI can switch to **simulation** (governance chain only).
- Always pair with a strong Dashboard token; do not expose Dashboard on untrusted networks.

---

## 5. Status vs traffic (keep them separate)

| Mechanism | Use for |
| --- | --- |
| **2D routing / weights / canary** | **Traffic** (do not invent a RuntimeStatus for “stop traffic”) |
| **Permissions / `LING_ENABLE`** | Control-plane enablement |
| **`INACTIVE`** | **Fact**: no usable instances (aggregation) |
| **Unload → STOPPING → REMOVED** | Full teardown and reclaim |

Do not express traffic shift via RuntimeStatus; change routing weights only.

---

## 6. Build matrix

- Primary path: Spring Boot 2.7 + JDK 8 (default profile `spring-boot2`)
- Support line: `-Pspring-boot3` + JDK 17
- Layout (no reflective Servlet probing):
  - Runtime: shared `lingframe-spring-boot-starter` + typed `lingframe-spring-boot2-starter` / `lingframe-spring-boot3-starter`
  - Dashboard: single GAV + `src/java-javax` / `src/java-jakarta` (and matching tests) via `build-helper`
- Always `clean` when switching matrices (SB3 class files break JDK 8)
- Contributor detail: [DEVELOPMENT_MANUAL.md](../../DEVELOPMENT_MANUAL.en.md) section 5.2
- 0.4 delivery: **control plane + routing elevation + correctness closure**; config and boundaries are listed in this checklist

---

## 7. Minimum checklist

- [ ] `dev-mode: false`
- [ ] Dashboard `allow-weak: false` + strong token
- [ ] Enable `ling-core-governance` as needed
- [ ] `security.strict-mode: true` (unless audited exemptions)
- [ ] Decide `force-drain-on-timeout` and drain timeout (default `true` force-tears-down after drain timeout)
- [ ] Shared API changes via restart / versioned packages
- [ ] Never promote example `123456` + open dev bypass to production
- [ ] Lings access DB **only via injected DataSource beans** (no `DriverManager` / private pools that bypass governance)

---

## 8. Copy-paste production profile fragment

```yaml
# application-prod.yaml (rotate the token per environment)
lingframe:
  enabled: true
  dev-mode: false
  ling-core-governance:
    enabled: true
    check-permissions: true
  security:
    strict-mode: true
  runtime:
    force-cleanup-delay: 60s
    force-drain-on-timeout: true   # set false if long TX must not be cut
  dashboard:
    enabled: true
    access-token:
      enabled: true
      token: "<rotate-me-strong-token>"
      allow-weak: false
```

Local examples may keep `dev-mode: true` and `allow-weak: true`. **Do not** reuse sample tokens under a `prod` profile.

---

## 9. Storage governance boundary (honest)

### 9.1 Which paths get proxied (must be honest)

| Path | Governed? | How |
| --- | --- | --- |
| Spring `DataSource` **Bean** in LingCore / Ling context | **Yes** (main path) | `DataSourceWrapperProcessor` → `LingDataSourceProxy` → `LingConnectionProxy` |
| Ling auto `spring.datasource.*` via `LingDataSourceRegistrar` | **Yes**, if registered as Spring Bean and then wrapped by BPP | Same proxy chain |
| `DriverManager.getConnection(...)` inside a Ling | **No** | No BeanPostProcessor hook |
| Hand-built pool / non-Bean `DataSource` held as field | **No** | Never enters wrapper |
| JDBC URL from arbitrary credentials | Blocked **on governed proxy only** (`getConnection(user,pass)` forbidden on proxy) | Direct driver calls still possible if bypassing proxy |

This is **organization-style** governance (inject + proxy), not a JVM sandbox. Production convention: lings access the database **only** via the injected DataSource.

### 9.2 Table-level multi-condition (AND)

When the proxy path is active and table-level permission / audit / rate-limit is configured:

- Governance points combine by strict **AND**: any one rejection blocks the SQL.
- For troubleshooting, correlate trace ID / governance log; permission is checked first, then rate limit.

### 9.3 Optional hardening (not required for 0.4)

| Approach | Effect | Cost |
| --- | --- | --- |
| Convention + Code Review / ArchUnit | Forbid lings from depending on `DriverManager` etc. | Low, recommended |
| Load-time scan extension (forbid DriverManager calls) | Reject at install time | Medium, false-positive risk |
| Java Agent / bytecode instrumentation | Enforce at runtime | High, complexity |
| Only expose managed DataSource to lings | No direct-connect entry architecturally | Medium, requires assembly convention changes |
