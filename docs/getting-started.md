# Getting Started

This document is the **formal getting-started guide**.

If you just want to get the examples up and running first, prioritize reading `QUICK_START.md` in the repository root.
This document, however, focuses on explaining what happens after you get it running:

- What exactly was started in the example?
- Why do these steps work?
- How should you continue understanding and using LingFrame?

If you only remember one sentence, remember this:

> LingFrame lets you load and govern isolated business lings within a single JVM process, rather than forcing you to split the system into microservices right from the start.

For the current public implementation, this is not just a demonstration of "getting lings loaded." It is your first encounter with a governable, convergent runtime chain that can later be verified for disciplined hot unloads.

---

## What You Are About to Run

In the example project, you will start a LingCore application and tell it to load two example lings:

- `user-ling`
- `order-ling`

In this single run, you will simultaneously see three things:

- Lings can be loaded within the same process.
- LingCore can invoke ling services via shared contracts.
- The invocation process still passes through the governance kernel.

---

## Environment Requirements

- JDK 17+ (as the main example path)
- Maven 3.8+

The current runtime simultaneously supports both JDK 8 and Spring Boot 2.x, but the example project remains the easiest entry point for beginners.

---

## Running in 5 Minutes

### 1. Clone the Repository

```bash
# GitHub
git clone https://github.com/LingFrame/LingFrame.git

# AtomGit
git clone https://atomgit.com/lingframe/LingFrame.git

# Gitee
git clone https://gitee.com/LingFrame/LingFrame.git
```

### 2. Build the Project

```bash
cd LingFrame
mvn clean install -DskipTests
```

### 3. Start the Example LingCore Application

```bash
cd lingframe-examples/lingframe-example-lingcore-app
mvn spring-boot:run
```

### 4. Verify the Example

```bash
curl http://localhost:8888/user-ling/user/listUsers
curl "http://localhost:8888/user-ling/user/queryUser?userId=1"
```

If both of these requests return normally, you already have a runnable LingFrame runtime.

---

## Taking Another 5 Minutes: Verifying Current Closed-Loop Governance

If you want to confirm that the current example doesn't just "run," but truly possesses a closed-loop control surface, observability, and unloading capability, you can continue with the following steps.

### 1. Open the Dashboard

Visit in your browser:

```text
http://localhost:8888/dashboard.html
```

You should see a list of currently loaded lings, as well as control surface information like health metrics, governance configs, and timelines.

### 2. View Current Lings and Versions

```bash
curl http://localhost:8888/lingframe/dashboard/lings
```

In the default example, you'll generally see:

- `order-ling:1.0.0`
- `user-ling:1.0.0`
- `user-ling:1.1.0-canary`

### 3. Check Health and Governance Metrics

```bash
curl http://localhost:8888/lingframe/dashboard/lings/health/all
curl http://localhost:8888/lingframe/dashboard/lings/governance/all
```

Here you can directly see:

- Ling-level summaries
- Version-level details
- Currently collected governance signals

### 4. Push a Governance Patch to `user-ling`

```bash
curl -X POST http://localhost:8888/lingframe/dashboard/governance/user-ling/invocation \
  -H "Content-Type: application/json" \
  -d "{\"timeoutMs\":3000,\"rateLimitPerSecond\":1,\"maxConcurrentThreads\":1}"
```

This step corresponds to the currently closed-loop invocation governance parameters:

- `timeoutMs`
- `rateLimitPerSecond`
- `maxConcurrentThreads`

### 5. Send Requests Again and Observe Changes

```bash
curl http://localhost:8888/user-ling/user/listUsers
curl http://localhost:8888/lingframe/dashboard/lings/health/all
curl http://localhost:8888/lingframe/dashboard/lings/governance/all
```

You should be able to see:

- Changes in request count, latency, and QPS in the health metrics.
- Signaling changes locally triggered by rate limit/timeouts in the governance metrics.

### 6. Verify Structured Unload Precheck

```bash
curl -X DELETE http://localhost:8888/lingframe/dashboard/lings/uninstall/user-ling/1.1.0-canary
```

What this step returns is no longer simply success/failure, but a structured unload result representing:

- Whether the unload was actually triggered.
- Overall risk level.
- A summary list of risks.

Note:

- The current default strategy is "prompt but do not block."
- So even if the precheck returns risk warnings, the main unload process may still proceed.
- The passive leak detection chain post-unload is still preserved, and has not been replaced by the pre-unload precheck.

---

## What Exactly Did You Just Start?

### LingCore

`LingCore` is the core-side application within the current process. It owns the runtime, the governance kernel, and the shared contract boundaries.

### Ling

`Ling` is the isolated business unit being deployed independently inside the LingCore process.

### Shared API

The `Shared API` is the process-level common contract layer bridging LingCore and Lings, or bridging between Lings. Interfaces and DTOs intended to cross boundaries belong here.

As a beginner, remember these three definitions:

- LingCore is the core application executing in the current process.
- Ling is the isolated business unit.
- Shared API is the mutually respected contract between them.

For terminology details, see the [Glossary](glossary.md).

---

## Minimal Viable Configuration

The example application arrives with functional configs. The most critical parts are these:

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

What this config expresses is:

- Enable the LingFrame runtime.
- Run in developer-friendly mode.
- Preload shared contracts before starting the lings.
- Discover lings from the local example source paths.

---

## What Does This Execution Prove?

When the example runs successfully, you have actually verified four things:

- LingCore can discover and load lings within a single process.
- Shared contracts are preloaded before lings start.
- Cross-ling invocations do not bypass the governance kernel.
- The current example configs are ready for you to read further into the development documentation.

If you continue and complete the Dashboard/governance/unload validations above, you'll additionally see:

- The control surface can hot-adjust invocation governance parameters.
- Health and governance metrics change following real requests.
- The pre-unload precheck, true unload run, and post-unload diagnostics have formed a primary chain.

The next thing most worth verifying is not just "can we load another ling," but whether this runtime chain can stay orderly under reload/unload/cleanup scenarios.

Next, if you want to judge how to adapt this, read [Practical Entry](practical-entry.md). If you want to dive straight into writing lings, jump to [Ling Development Guide](ling-development.md).  
Before production, read the [Production Hardening Checklist](production-hardening.md).
