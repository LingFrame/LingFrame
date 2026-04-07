<h1 align="center">灵珑 · LingFrame</h1>

<p align="center">
  <strong>A JVM Runtime Governance Framework For Long-Running Systems</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Status-Resilience_Governance-brightgreen" alt="Status">
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue" alt="License">
  <img src="https://img.shields.io/badge/Java-17-orange" alt="Java">
  <img src="https://img.shields.io/badge/Java-8-orange" alt="Java">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.6-brightgreen" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Spring_Boot-2.7.18-brightgreen" alt="Spring Boot">
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
  <a href="../../pulls">
    <img src="https://img.shields.io/badge/PRs-welcome-brightgreen" alt="PRs Welcome">
  </a>
  <a href="https://deepwiki.com/LingFrame/LingFrame">
    <img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki">
  </a>
</p>

<p align="center">
  <strong>English</strong> | <a href="./README.zh-CN.md">中文</a>
</p>

LingFrame is a **JVM runtime governance framework for long-running systems**.

It does not ask you to rewrite the system immediately, and it does not force an instant move to microservices.  
It cares more about something more practical:

> When a system has already been running for many years, cannot easily stop, and keeps getting harder to change,  
> can we first make it understandable, controllable, and evolvable again?

Many systems are not poorly designed.  
They have simply lived too long and changed too fast.

If you remember LingFrame in only one sentence, let it be this:

> LingFrame is not only responsible for loading lings into one JVM,  
> but also for keeping them governable, convergent, and cleanly unloadable over long-running runtime life.

---

## 📖 Start Here

- **Want the shortest runnable path**: start with [QUICK_START.md](./QUICK_START.md)
- **Want the full onboarding path**: start with [getting-started.md](docs/getting-started.md)
- **Want to quickly understand what problem it solves**: read [practical-entry.md](docs/practical-entry.md)
- **Want to see how far the current architecture has already converged**: read [technical-entry.md](docs/technical-entry.md)
- **Want to understand why LingFrame is designed this way**: read [WHY.md](WHY.md)
- **Want to understand what LingFrame insists on**: read [MANIFESTO.md](MANIFESTO.md)

> You do not need to read the whole documentation set in one pass.  
> LingFrame lets you stop at any point and continue later.

![LingFrame Dashboard Example](./docs/images/dashboard.png)

*The current dashboard is already a usable governance control surface, not just a display page.*

---

## ✨ What Is LingFrame?

LingFrame is not just a plugin framework with a different name.  
It is not a universal cure for monolith modernization either.

More precisely, it is:

- a runtime governance framework for long-running single-process systems
- a structural tool that helps legacy systems recover boundaries and control
- a governance model that allows lings to exist, but does not tolerate ling chaos

Its focus is not to add one more layer of features,  
but to bring back under order the complexity that already exists in the system and is becoming increasingly uncontrolled.

---

## 💎 What Makes LingFrame Different

- **It does not only emphasize hot loading; it emphasizes disciplined hot unload**: a ling is not considered done just because it can go offline, it should go through draining, cleanup, resource release, and state convergence
- **It treats zero-leak hot unload as a formal goal**: not simply by dropping the `ClassLoader`, but by making unload cleanup, resource eviction, and leak diagnostics part of long-running runtime mechanisms
- **It converges long-running runtime order into one spine**: invocation governance, runtime state, control surface, and monitoring events are not scattered pieces, but are being converged into one runtime kernel
- **It stays strict about hot-update boundaries**: for process-level contracts such as `Shared API`, it does not market unsafe-but-cool hot-update abilities

---

## 🎯 What It Fits, What It Does Not

### Better Fit

- monolithic systems that have been running for years and cannot be easily stopped or rewritten
- teams that want to gradually introduce ling isolation, canary release, rate limiting, circuit breaking, permission, and audit capabilities
- scenarios where runtime order needs to be restored first without completely overturning the existing system

### Not A Good Fit

- treating it as a replacement for microservices
- treating it as a pure front-end plugin marketplace or low-code assembly platform
- expecting one framework to eliminate business complexity automatically

LingFrame does not make decisions for the system.  
It only tries to put decisions back where they should happen.

---

## 🚀 Current Implemented Capabilities

The current implementation focus is not to keep adding scattered outward-facing capabilities,
but to truly converge the governance mechanisms that already exist into a stable, reusable, explainable runtime spine.

The core scope clearly delivered in the current release includes:

- converging a unified governance pipeline around `InvocationPipelineEngine`, terminating the proliferation of magic keys through a partitioned `InvocationContext`
- making three execution modes explicit: `NORMAL`, `SIMULATION`, and `GOVERN_ONLY`
- letting ling invocation, Spring Boot 2 / 3 web governance, LingCore bean interception, and dashboard simulation share the same governance kernel
- converging runtime state into the dual-layer model of `InstanceStatus` and `RuntimeStatus`, written respectively by `InstanceCoordinator` and `RuntimeCoordinator`
- making `DefaultLingLifecycleEngine` explicitly responsible for lifecycle orchestration of deploy, reload, and unload
- formally bringing unload cleanup, resource eviction, and leak diagnostics into long-running runtime responsibilities, continuing to converge toward disciplined hot unload and a zero-leak goal
- letting `SharedApiManager` make the bootstrap boundary of Shared API explicit: preload, register packages, freeze the boundary, then load lings
- making the dashboard a real governance control surface with lifecycle operations, canary configuration, governance patches, simulation, metrics, and SSE event streams

In other words, LingFrame is no longer just a mechanism assembly.  
It is converging toward a runtime governance kernel that can truly be maintained over the long term.

---

## 🧩 The Problem It Really Tries To Solve

In real systems, the issue is often not missing features, but the growing frequency of situations like these:

- the system is still running, but no one dares to touch it
- boundaries still exist in name, but in reality they are getting blurrier and blurrier
- canary, circuit breaking, permission, and audit each exist somewhere, but without one unified runtime landing point
- restart is not absolutely unacceptable; unpredictability is

What LingFrame really cares about is only one question:

> How do we keep a system from falling out of control during long-running life?

Not by piling on more rules,  
but through clearer boundaries, a more stable governance spine, and more honest runtime feedback.

---

## ⚙️ Technical Boundaries

- JVM: JDK 17 / JDK 8
- Spring Boot: 3.x / 2.x
- the current public architecture is still ling isolation and governance inside a single process
- `Shared API` is a process-level public contract boundary: a brand-new shared package may be hot-loaded, but an already loaded contract does not support hot update or hot unload; contract changes require a process restart
- native support exists for canary release, circuit breaking, rate limiting, audit, permission, simulation, and governance observability
- external registries, configuration centers, and similar infrastructure may be integrated non-invasively

LingFrame does not pretend complexity does not exist.  
It simply refuses to dump all complexity onto the user at once.

---

## 💬 If You Want To Continue

- Want the shortest hands-on path: read [QUICK_START.md](./QUICK_START.md)
- Want the full onboarding path: read [getting-started.md](docs/getting-started.md)
- Want to understand the current public capabilities from the implementation side: read [technical-entry.md](docs/technical-entry.md)
- Want to see what role the dashboard plays in the current runtime: read [dashboard.md](docs/dashboard.md)
- Want terminology help: read [glossary.md](docs/glossary.md)

If you stop here, that is completely fine too.

---

## 🙏 Acknowledgments

**Special thanks to Gitee official channels and the open-source community for their recommendation and support!**

Thanks to [Gitee](https://gitee.com) and Teacher Hongshu for providing fertile ground for the local open-source ecosystem, so that even foundational wheels can be seen.
👉 [Visit the official Gitee primary repository](https://gitee.com/LingFrame/LingFrame)

---

[![AtomGit](docs/images/AtomGit.svg)](https://atomgit.com/lingframe/LingFrame)

This project is an **AtomGit G-Star Incubated Project**.  
Thanks to [AtomGit](https://atomgit.com) for supporting and promoting open-source projects.
