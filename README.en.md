<h1 align="center">灵珑 · LingFrame</h1>

<p align="center">
  <strong>Safely evolve long-running JVM systems without rewrites.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-0.4.0-blue" alt="Version">
  <img src="https://img.shields.io/badge/Stage-Pre--1.0-yellow" alt="Stage">
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue" alt="License">
  <img src="https://img.shields.io/badge/Java-8_(default)_%7C_17-orange" alt="Java">
  <img src="https://img.shields.io/badge/Spring_Boot-2.7_(default)_%7C_3.5-brightgreen" alt="Spring Boot">
</p>

<p align="center">
  <a href="https://gitee.com/LingFrame/LingFrame">
    <img src="https://img.shields.io/badge/Gitee-Repository-red?logo=gitee&logoColor=white" alt="Gitee">
  </a>
  <a href="https://atomgit.com/lingframe/LingFrame">
    <img src="https://img.shields.io/badge/AtomGit-G--Star_Incubated-silver?logo=git&logoColor=white" alt="AtomGit">
  </a>
  <a href="https://github.com/LingFrame/LingFrame">
    <img src="https://img.shields.io/badge/GitHub-Repository-black?logo=github&logoColor=white" alt="GitHub">
  </a>
  <a href="https://deepwiki.com/LingFrame/LingFrame">
    <img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki">
  </a>
</p>

<p align="center">
  <a href="./README.md">中文</a> | <strong>English</strong>
</p>

As a system runs longer, business keeps evolving, and the codebase grows too massive for anyone to refactor safely.

LingFrame is a **JVM runtime governance framework for long-running systems**. It allows you to isolate new capabilities or legacy modules into independent **lings** (Ling—a business unit independently loaded, run, governed, and unloaded inside a JVM), enabling live canary, observation, rollback, and unloading without rebuilding or redeploying the entire system.

It does not dictate how many microservices your system should be split into; it solves how each JVM process evolves internally. **It can be adopted at any stage of a system's lifecycle**—no total rewrite or rushed microservice split required.

Positioning Diagram (LingFrame level in overall system architecture):

```text
               System Architecture Dimension (Inter-Process)
     Monolith       Modular Monolith     Microservices
        │                   │                  │
        └───────────────────┼──────────────────┘
                            ▼
                  Inside Any JVM Process
                            │
               LingFrame Runtime Governance
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
           ling A        ling B        ling C
              │             │             │
           Route · Canary · Isolation · Unload
```

---

## How to solve problems: Try first, observe, converge last

In production, risk management comes first. LingFrame encourages a gradual migration path:

```text
Old impl (in LingCore — your main process / or ling v1)  ──keeps serving traffic──►
                                                                    │
  New capability as ling v2 (keep old code untouched)  ──weight route-in──►  observe
                                                                    │
                             Data/signals/health OK? ──┬─ no  → revoke weight / unload v2
                                                       └─ yes → then decide whether to retire old path
```

Key points:

| Step | Meaning |
| --- | --- |
| Leave old code untouched | No forced front-loaded refactoring costs |
| New features in new lings | Changes land on clean boundaries instead of piling on legacy code |
| In-process dual versions | Parallel execution inside one JVM, not whole-process blue-green flips |
| Traffic weights over switches | Shift 5% traffic to observe real logs instead of flipping status switches |
| Retire only after certainty | Decommissioning old paths is a decision based on evidence, not a release prerequisite |

### Why this is safer

* **Independent Releases**: Fast and slow features don't have to share a release cycle. A new version can be installed, traffic-shifted, and unloaded independently.
* **No Release Day Gambling**: Avoid big-bang migrations with incomplete information. Coexist, observe with real metrics, and keep a clean fallback path.
* **Smoother Convergence**: Decommissioning old paths is a choice made after thorough validation, not an anxious bet placed on release day.

---

## What you get

| Capability | What it is | What problem it solves |
| --- | --- | --- |
| In-process isolation | Type-level isolation for lings in a single JVM (not "absolute static isolation") | Lings in the same process don't clash on Classes; legacy code isn't broken by new dependencies |
| Disciplined load / unload | Ordered lifecycle; unload aims for a **provably GC-collectable** classloader | Load and unload cleanly, ensuring memory resources are genuinely reclaimed |
| Multi-version & Canary | Concurrent versions; traffic shifted by **weights** | Release gradually without downtime; roll back immediately if errors occur |
| Call governance | Unified rate limiting, bulkhead, timeout, permission, and audit policies | All policies run on a single pipeline spine instead of being re-implemented per endpoint |
| Control plane | Dashboard for lifecycle, canary, simulation, and live signals | Observe real-time runtime events and intervene whenever necessary |
| Dual-layer state model | Instance facts vs runtime aggregate — separate write authorities | Clear ownership of state transitions, preventing race conditions under concurrency |
| Dual Spring stack | **Default:** Spring Boot 2.7 + JDK 8 · **Support:** Boot 3.5 + JDK 17 | No need to rewrite for Spring Boot 3; both stacks are natively supported |

### Runtime Architecture

```text
┌───────────────────────────── LingCore (your main process) ─────────────────────────┐
│  Shared API (process contract — freeze after load; no hot-unload of shared boundary)│
│                                                                                   │
│   ┌──────────┐   Pipeline: route · guard · canary · resilience · permission · …   │
│   │ Dashboard│ ─────────────────────────────────────────────────────────────────►│
│   └──────────┘                         │                                          │
│                                        ▼                                          │
│              ┌──────────── ling A v1 ────┐   ┌──── ling A v1.1-canary ────┐       │
│              │  LingClassLoader (child)  │   │  LingClassLoader (child)   │       │
│              └───────────────────────────┘   └────────────────────────────┘       │
│                                                                                   │
│   Instance layer = real lifecycle of one version                                  │
│   Runtime layer  = macro presentation of a ling id                                │
└───────────────────────────────────────────────────────────────────────────────────┘
```

Design stance: [WHY.md](WHY.en.md) · [MANIFESTO.md](MANIFESTO.en.md)

---

## Shortest path to run

Need a JDK (**examples default to 8**; support line may use 17) and Maven.

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am package -DskipTests
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

- App: `http://localhost:8888`  
- Dashboard: `http://localhost:8888/dashboard.html`  

```powershell
curl http://localhost:8888/lingframe/dashboard/lings
curl http://localhost:8888/user-ling/user/listUsers
```

Command detail: [QUICK_START.md](QUICK_START.en.md)

After it boots:

1. Open the Dashboard and confirm sample lings are loaded  
2. Send another business request and confirm the path works  
3. Check monitor / governance for live signals  
4. With multiple versions, change traffic by **weights** — do not use runtime status as a traffic switch  

![LingFrame Dashboard](./docs/en/images/dashboard.png)

Two example tracks (see [lingframe-examples/README.en.md](lingframe-examples/README.en.md) for the map):

| Track | Entry |
| --- | --- |
| Getting started | `lingframe-example-lingcore-app` + user / order |
| Legacy gradual migration | `ling-mall` → `saas-mall` |

---

## What it fits, what it does not

### Best fit

- Monoliths that have run for years and cannot be easily stopped or rewritten;
- Teams that want isolation, canary, rate limiting, circuit breaking, permission, and audit gradually;
- Scenarios where runtime order must be restored without overturning the existing system.

### Also suitable for

- **New Projects**: Teams wanting to establish clean boundaries using "lings" from Day 1 to avoid legacy messes;
- **Large Microservices**: Establishing evolution boundaries inside bloated microservices ("distributed monoliths");
- Systems requiring independent release and governance schedules for different modules.

### Not a good fit

- **Treating it as a microservice replacement**: LingFrame governs in-process code evolution, not cross-service network communication. They are complementary, not mutually exclusive;
- Treating it as a front-end plugin marketplace or low-code assembly platform;
- Expecting one framework to eliminate business complexity automatically.

### Vs common paths

| Path | Where it often hurts | LingFrame focus |
| --- | --- | --- |
| Homegrown loaders / nested containers | Load works; manage / unload / observe is hard | Load-unload, unified governance, console |
| Generic plugin frameworks | Modularity exists; long-run governance is DIY | Control and unload for long-running life |
| Jump to microservices | Strong isolation, high cost and risk | Rebuild in-process boundaries when you cannot split yet |
| Gateway-only canary | Edge can split traffic; the process is still a mess | In-process versions, call governance, visibility, reclaim |

### Design Boundaries & Current Limitations

- Public scope is **in-process** ling isolation and governance;
- Shared API is a process-level contract: new packages can be preloaded; **contracts already in the shared boundary are not hot-updated or hot-unloaded** (an architectural trade-off to ensure type safety; breaking changes require a restart);
- Storage governance mainly covers Spring-injected DataSources, not every hand-rolled JDBC path;
- Dangerous-API scanning is load-time signaling, not a full JVM security sandbox;
- Primary verification path (**examples default**): Spring Boot 2.7 + JDK 8; Spring Boot 3 + JDK 17 is the support line (runtime dual starters + dashboard single-GAV matrix sources; see [DEVELOPMENT_MANUAL](DEVELOPMENT_MANUAL.en.md) §5.2);
- **0.4.0 is Pre-1.0**: evaluate with examples and [production hardening](docs/en/production-hardening.md) before production.

---

## Minimal adopt (into your LingCore)

Artifacts ship from this repository (install locally first if you do not already have them on a private registry):

```powershell
mvn -pl lingframe-bom,lingframe-runtime/lingframe-spring-boot2-starter,lingframe-dashboard -am install -DskipTests
```

In your LingCore, pull in `lingframe-bom` (dependencyManagement) + `lingframe-spring-boot2-starter` (Spring Boot 2.7 / JDK 8 default); for Spring Boot 3 / JDK 17 use `lingframe-spring-boot3-starter` instead (and build this repo with `-Pspring-boot3`). Optionally add `lingframe-dashboard` for the control plane. Full coordinates in getting-started.

**LingCore `application.yml` (skeleton):**

```yaml
lingframe:
  enabled: true
  dev-mode: true          # local only — turn off for production
  ling-home: "lings"      # directory of packaged lings
  # preload-api-jars: [ "path/to/shared-api" ]
```

**Ling `ling.yml` (skeleton):**

```yaml
id: user-ling
version: 1.0.0
mainClass: "com.example.UserLing"
```

Full walkthrough: [docs/en/getting-started.md](docs/en/getting-started.md) · write a ling: [docs/en/ling-development.md](docs/en/ling-development.md) · before production: [docs/en/production-hardening.md](docs/en/production-hardening.md)

---

## Performance (kernel microbench)

In most business scenarios, overhead from SQL, RPC, and serialization far exceeds the governance chain, so framework overhead is rarely the primary bottleneck.

Public JMH sample (empty business body / reflective terminal; **not** SQL or RPC):  
[`benchmark-results-20260709-044113.txt`](lingframe-benchmark/benchmark-results-20260709-044113.txt)

| Path | 1-thread ≈ / op | Hot-path alloc | Scale note |
| --- | ---: | ---: | --- |
| Full chain + terminal invoke | **~19 µs** | ≪ 1 B/op | 1→8 threads ≈ **6.8×** throughput |
| Governance-only (Web/AOP stays host-side) | **~0.8 µs** | ~0 | 1→8 threads ≈ **4.1×** |

Reproduce: [`lingframe-benchmark/README.md`](lingframe-benchmark/README.md). Load-test your own workload before production.

---

## Go further

| Goal | Doc |
| --- | --- |
| Read by stage | [docs/en/README.md](docs/en/README.md) |
| Integrate, write lings, configure | [docs/en/README.md — Adopt and develop](docs/en/README.md) |
| Production config | [production-hardening](docs/en/production-hardening.md) |
| Architecture | [architecture](docs/en/architecture.md) |
| This release | [CHANGELOG](CHANGELOG.en.md) |
| Contribute | [CONTRIBUTING](CONTRIBUTING.en.md) · [DEVELOPMENT_MANUAL](DEVELOPMENT_MANUAL.en.md) |

---

## Acknowledgements

Special thanks to Gitee and the open-source community for recommendation and support.

Thanks to [Gitee](https://gitee.com) for soil where foundational wheels can still be seen.  
👉 [Gitee repository](https://gitee.com/LingFrame/LingFrame)

---

[![AtomGit](docs/en/images/AtomGit.svg)](https://atomgit.com/lingframe/LingFrame)

This project is an **AtomGit G-Star Incubated Project**.  
Thanks to [AtomGit](https://atomgit.com) for support and promotion.
