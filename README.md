# LingFrame

![Status](https://img.shields.io/badge/Status-Resilience_Governance-brightgreen)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)
![Java](https://img.shields.io/badge/Java-17-orange)
![Java](https://img.shields.io/badge/Java-8-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-brightgreen)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-2.7.18-brightgreen)

[![Gitee](https://img.shields.io/badge/Gitee-Repository-red?logo=gitee&logoColor=white)](https://gitee.com/LingFrame/LingFrame)
[![AtomGit G-Star](https://img.shields.io/badge/AtomGit-G--Star_Incubated-silver?logo=git&logoColor=white)](https://atomgit.com/lingframe/LingFrame)
[![GitHub](https://img.shields.io/badge/GitHub-Repository-black?logo=github&logoColor=white)](https://github.com/LingFrame/LingFrame)

[![Help Wanted](https://img.shields.io/badge/PRs-welcome-brightgreen)](../../pulls)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/LingFrame/LingFrame)

[中文](./README.zh-CN.md)

LingFrame is a **JVM runtime governance framework for long-running systems**.

It does not ask you to rewrite the system immediately, and it does not force an instant move to microservices.  
It focuses on something more practical:

> When a system has been running for years, cannot easily stop, and keeps getting harder to change,  
> can we first make it understandable, controllable, and evolvable again?

Many systems are not poorly designed.  
They have simply lived too long and changed too fast.

If you remember only one sentence about LingFrame, let it be this:

> LingFrame is not only about loading lings into one JVM.  
> It is about keeping them governable, convergent, and cleanly unloadable over long-running runtime life.

---

## Start Here

- **First time reading LingFrame**: [getting-started.md](docs/getting-started.md)
- **Want to understand what problem it solves**: [practical-entry.md](docs/practical-entry.md)
- **Want to see what `0.3.0` actually delivers**: [technical-entry.md](docs/technical-entry.md)
- **Want to understand why it is designed this way**: [WHY.md](WHY.md)
- **Want to understand what LingFrame stands for**: [MANIFESTO.md](MANIFESTO.md)

You do not need to read everything in one pass.  
LingFrame lets you stop at any point and continue later.

---

![LingFrame Dashboard Example](./docs/images/dashboard.0.3.0.png)

*The dashboard is now a real governance control surface, not just a demo page.*

---

## What LingFrame Is

LingFrame is not just a plugin framework with a new name.  
It is not a silver bullet for monolith modernization either.

More precisely, it is:

- a runtime governance framework for long-running single-process systems
- a structural tool that helps legacy systems recover boundaries and control
- a governance model that allows lings to exist, but does not tolerate ling chaos

Its goal is not to pile on one more layer of features.  
Its goal is to bring already-existing but increasingly uncontrolled complexity back under order.

---

## What Makes LingFrame Different

- **It does not stop at hot loading; it cares about disciplined hot unload**: a ling should not just disappear, it should drain, clean up, release resources, and converge its state properly
- **Zero-leak hot unload is treated as a formal goal**: not just "drop the classloader and hope", but unload cleanup, resource eviction, and leak diagnostics as part of runtime design
- **Long-running runtime order is converged into one spine**: invocation governance, runtime state, control surface, and monitoring evidence are being pulled into one runtime kernel instead of scattered mechanisms
- **It stays strict about hot-update boundaries**: process-level contracts such as `Shared API` are not marketed as "freely hot-updatable" when that would be unsafe

---

## What It Fits, What It Does Not

### Good Fit

- monolithic JVM systems that have been running for years and cannot be easily stopped or rewritten
- teams that want to gradually introduce ling isolation, canary release, rate limiting, circuit breaking, permission, and audit capabilities
- environments where runtime order must be restored before any large-scale structural rewrite is realistic

### Not A Good Fit

- treating it as a replacement for microservices
- treating it as a front-end plugin marketplace or low-code assembly platform
- expecting one framework to remove business complexity automatically

LingFrame does not make decisions for your system.  
It tries to put decisions back where they belong.

---

## Current Stage

**v0.3.0 - Convergence and Stabilization**

This stage is not about adding another batch of scattered capabilities.  
It is about converging existing governance mechanisms into a stable, reusable, explainable runtime spine.

What `0.3.0` clearly delivers:

- a unified governance pipeline around `InvocationPipelineEngine` and `FilterRegistry`
- explicit execution modes: `NORMAL`, `SIMULATION`, and `GOVERN_ONLY`
- the same kernel reused across ling invocation, Spring Boot 2 / 3 web governance, LingCore bean interception, and dashboard simulation
- converged runtime state ownership through `InstanceStatus`, `RuntimeStatus`, `InstanceCoordinator`, and `RuntimeCoordinator`
- explicit lifecycle orchestration through `DefaultLingLifecycleEngine`
- unload cleanup, resource eviction, and leak diagnostics treated as long-running runtime responsibilities, with the architecture converging toward disciplined low-leak hot unload
- an explicit shared API bootstrap boundary enforced by `SharedApiManager`
- a dashboard that acts as a real control surface with lifecycle operations, canary configuration, governance patching, simulation, metrics, health snapshots, and SSE event streaming

In other words, LingFrame is no longer just a collection of mechanisms.  
It is converging into a runtime governance kernel that can be maintained over time.

---

## The Problem It Actually Tries To Solve

In real systems, the issue is often not missing features. It is this:

- the system is still running, but no one dares to touch it
- boundaries still exist in name, but not in reality
- canary release, circuit breaking, permission, and audit each exist somewhere, but not on one clear runtime path
- restarts are not impossible, but unpredictability is unacceptable

LingFrame is really trying to answer one question:

> How do we keep a long-running system from falling out of control?

Not through more rules alone,  
but through clearer boundaries, a more stable governance spine, and more honest runtime feedback.

---

## Technical Boundaries

- JVM: JDK 17 / JDK 8
- Spring Boot: 3.x / 2.x
- the current public architecture is still single-process ling isolation and governance
- `Shared API` is a process-level public contract boundary: a brand-new shared package may be hot-loaded, but an already loaded contract must not be hot-updated or hot-unloaded; contract changes require a process restart
- native support exists for canary release, circuit breaking, rate limiting, audit, permission, simulation, and governance visibility
- external registry and configuration systems can be integrated non-invasively

LingFrame does not pretend complexity does not exist.  
It just refuses to dump all of it on the user at once.

---

## If You Want To Continue

- Want to run it first: [getting-started.md](docs/getting-started.md)
- Want to understand `0.3.0` from the implementation side: [technical-entry.md](docs/technical-entry.md)
- Want to see the role of the dashboard in the current release: [dashboard.md](docs/dashboard.md)
- Want terminology help: [glossary.md](docs/glossary.md)

If you stop here, that is completely fine.

---

## Acknowledgments

[![AtomGit](docs/images/AtomGit.svg)](https://atomgit.com/lingframe/LingFrame)

This project is an **AtomGit G-Star Incubated Project**.  
Thanks to [AtomGit](https://atomgit.com) for supporting and promoting open source projects.
