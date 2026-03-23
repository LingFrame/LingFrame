# Getting Started

This guide is the shortest path for a first-time LingFrame user.

It is intentionally about one thing only: **getting a runnable example up and understanding the minimum vocabulary**.

If you only want one thing from this page, remember this:

> LingFrame helps you load and govern isolated lings inside one JVM process, without forcing a microservice rewrite.

For `0.3.0`, this is not only a demo of "lings can be loaded".  
It is also your first look at a runtime path that is governable, convergent, and ready to be validated later against disciplined unload behavior.

---

## What You Will Run

In the example project, you will start one LingCore application and let it load two sample lings:

- `user-ling`
- `order-ling`

You will see three things in one run:

- lings can be loaded into the same process
- LingCore can call ling services through shared contracts
- governance still sits in the middle of that call path

---

## Prerequisites

- JDK 17+ for the main example path
- Maven 3.8+

`0.3.0` also supports JDK 8 and Spring Boot 2.x, but the example app remains the easiest place to start.

---

## 5-Minute Run

### 1. Clone the repository

```bash
# GitHub
git clone https://github.com/LingFrame/LingFrame.git

# AtomGit
git clone https://atomgit.com/lingframe/LingFrame.git

# Gitee
git clone https://gitee.com/LingFrame/LingFrame.git
```

### 2. Build the project

```bash
cd LingFrame
mvn clean install -DskipTests
```

### 3. Start the example LingCore application

```bash
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

### 4. Verify the example is alive

```bash
curl http://localhost:8888/user-ling/user/listUsers
curl "http://localhost:8888/user-ling/user/queryUser?userId=1"
```

If both requests return normally, you already have a working LingFrame runtime.

---

## What Just Started

### LingCore

`LingCore` is the LingCore-side application inside the current process. It owns the runtime, the governance kernel, and the shared contract boundary.

### Ling

A `Ling` is a separately loaded business runtime unit with its own classloader and lifecycle.

### Shared API

`Shared API` is the process-level contract layer. Interfaces and DTOs that cross the LingCore / ling boundary belong here.

For a beginner, it is enough to remember:

- LingCore is the LingCore-side application in the current process
- lings are isolated business runtime units
- Shared API is the contract both sides agree on

For terminology details, see [Glossary](glossary.md).

---

## Minimal Example Configuration

The example app already contains working configuration. The important parts are:

```yaml
server:
  port: 8888

lingframe:
  enabled: true
  dev-mode: true

  preload-api-jars:
    - lingframe-examples/lingframe-example-order-api

  ling-home: lings
  ling-roots:
    - lingframe-examples/lingframe-example-ling-order
    - lingframe-examples/lingframe-example-ling-user
```

This means:

- LingFrame runtime is enabled
- the process runs in developer-friendly mode
- shared contracts are preloaded before lings start
- local example lings are discovered from source roots

---

## What This Run Already Proves

Once the example is up, you have already verified four things:

- LingCore can discover and load lings inside one process
- shared contracts are preloaded before lings start
- cross-ling calls do not bypass the governance kernel
- the example setup is enough to continue with real development docs

The next thing worth validating is not only whether another ling can be loaded, but whether the same runtime path stays orderly through reload, unload, and cleanup scenarios.

Continue with [Practical Entry](practical-entry.md) for rollout decisions, or go straight to [Ling Development Guide](ling-development.md) if you want to start writing lings.
