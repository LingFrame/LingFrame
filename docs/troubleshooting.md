# Troubleshooting Guide

This document helps you quickly identify and resolve common LingFrame runtime issues.

---

## Quick Diagnosis Flowchart

```
Problem Occurred
    │
    ├─→ Ling won't load? ──────────────────→ See [ClassLoader Issues]
    │
    ├─→ Ling won't start? ─────────────────→ See [Lifecycle Issues]
    │
    ├─→ Invocation fails or times out? ────→ See [Invocation Chain Issues]
    │
    ├─→ Memory keeps growing? ─────────────→ See [Memory Leak Issues]
    │
    ├─→ State anomaly? ────────────────────→ See [State Machine Issues]
    │
    └─→ Other issues ──────────────────────→ See [Log Analysis]
```

---

## 1. ClassLoader Issues

### 1.1 ClassNotFoundException / NoClassDefFoundError

**Symptoms:**
```
java.lang.ClassNotFoundException: com.example.MyClass
java.lang.NoClassDefFoundError: com/example/MyClass
```

**Possible Causes:**

| Cause | Diagnosis | Solution |
|-------|-----------|----------|
| Class not in ling JAR | Check JAR contents | Ensure class is packaged |
| Class in Shared API but not registered | Check `preload-api-jars` config | Add shared package to config |
| Class incorrectly delegated to parent | Check if class matches delegation rules | Adjust delegation package config |
| Missing ling dependency | Check `ling.yml` dependencies | Add missing dependencies |

**Diagnostic Commands:**
```bash
# View JAR contents
jar -tf your-ling.jar | grep MyClass

# Check class loading path
# Search in logs
grep "ClassLoader" logs/lingframe.log
```

### 1.2 ClassCastException / LinkageError

**Symptoms:**
```
java.lang.ClassCastException: com.example.MyClass cannot be cast to com.example.MyClass
java.lang.LinkageError: loader constraint violation
```

**Cause:** The same class was loaded by different ClassLoaders.

**Diagnostic Steps:**

1. Check if the same class exists in both Shared API and ling
2. Check if Shared API boundary was frozen after ling loading
3. Check if previous ling was not fully unloaded after hot update

**Solution:**
```yaml
# Ensure Shared API is preloaded and frozen before ling loading
lingframe:
  preload-api-jars:
    - /path/to/shared-api.jar
  freeze-shared-api-before-ling-load: true
```

### 1.3 File Locked After Ling Unload (Windows)

**Symptoms:**
```
java.io.FileNotFoundException: The process cannot access the file because it is being used by another process
```

**Cause:** JAR file handle not released on Windows platform.

**Diagnosis:**
```bash
# Use Process Explorer to view file handles
# Or search in logs
grep "close ClassLoader" logs/lingframe.log
```

**Solutions:**

1. Use JDK 8+, lower versions have incomplete ClassLoader closing
2. Check if ling code has static variables holding ClassLoader references
3. Enable leak detection:
```yaml
lingframe:
  dev-mode: true  # Enable DEV_AGGRESSIVE leak diagnostics in development mode
```

---

## 2. Lifecycle Issues

### 2.1 Ling Stuck in LOADING State

**Symptoms:** Dashboard shows ling status as LOADING for extended time.

**Possible Causes:**

| Cause | Diagnosis |
|-------|-----------|
| Security verification taking time | Check `LingSecurityVerifier` logs |
| Spring Context slow startup | Check ling Spring Bean initialization logs |
| Dependency service unavailable | Check ling external dependency connection status |

**Diagnostic Logs:**
```
# Search for lifecycle events
grep "LingLifecycleEngine\|InstanceStateChangedEvent" logs/lingframe.log

# Search for stuck phase
grep "LOADING\|STARTING" logs/lingframe.log
```

### 2.2 Ling Startup Fails and Enters ERROR State

**Symptoms:**
```
Instance [my-ling] v1.0.0 state changed: STARTING -> ERROR
```

**Diagnostic Steps:**

1. View error logs:
```bash
grep -A 20 "ERROR.*my-ling" logs/lingframe.log
```

2. Common error types:

| Error Type | Typical Log | Solution |
|------------|-------------|----------|
| Bean creation failed | `Error creating bean` | Check Spring configuration |
| Dependency injection failed | `No qualifying bean` | Check `@LingReference` configuration |
| Permission denied | `Permission denied` | Check capabilities in `ling.yml` |
| Port conflict | `Port already in use` | Modify ling port configuration |

### 2.3 Ling Cannot Be Unloaded

**Symptoms:** Unload operation times out or gets stuck.

**Diagnosis:**
```bash
# View active request count
grep "activeRequests" logs/lingframe.log

# View unload progress
grep "STOPPING\|drain\|unload" logs/lingframe.log
```

**Possible Causes:**

1. **Requests not completed**: Wait for requests to drain
2. **Background threads not stopped**: Check if ling created non-daemon threads
3. **Resources not released**: Check database connections, thread pools, etc.

**Force Unload (use with caution):**
```yaml
lingframe:
  runtime:
    force-cleanup-delay-seconds: 30  # Force cleanup after timeout
```

---

## 3. Invocation Chain Issues

### 3.1 Invocation Timeout

**Symptoms:**
```
LingInvocationException: Invocation timeout for service [my-ling:MyService.doSomething]
```

**Diagnostic Steps:**

1. Check timeout configuration:
```yaml
# In ling.yml
governance:
  timeout-ms: 5000
```

2. Check target ling status:
```bash
grep "RuntimeStatus\|InstanceStatus" logs/lingframe.log | grep my-ling
```

3. Check for circuit breaker:
```bash
grep "CircuitBreaker\|OPEN" logs/lingframe.log
```

### 3.2 Invocation Rejected

**Symptoms:**
```
LingInvocationException: Call rejected by governance
```

**Possible Causes:**

| Cause | Log Keyword | Solution |
|-------|-------------|----------|
| Circuit breaker open | `CircuitBreaker OPEN` | Wait for recovery or adjust threshold |
| Rate limiter triggered | `RateLimiter rejected` | Adjust rate limit configuration |
| Permission denied | `Permission denied` | Check capabilities configuration |
| Macro state abnormal | `RuntimeStatus=DEGRADED` | Check ling health status |

### 3.3 Canary Routing Not Working

**Symptoms:** Traffic not routed to canary instance.

**Diagnosis:**

1. Check canary configuration:
```yaml
# In ling.yml
governance:
  canary:
    enabled: true
    weight: 30  # 30% traffic
```

2. Check instance labels:
```bash
grep "labels\|canary" logs/lingframe.log
```

3. Check routing strategy:
```bash
grep "CanaryRouting\|LabelMatchRouter" logs/lingframe.log
```

---

## 4. Memory Leak Issues

### 4.1 Heap Memory Continuously Growing

**Symptoms:** JVM heap memory usage keeps rising, Full GC cannot reclaim.

**Diagnostic Steps:**

1. Enable leak detection:
```yaml
lingframe:
  dev-mode: true  # Enables DEV_AGGRESSIVE and DEV_BOUNDED diagnostics
  # Production mode (dev-mode: false) automatically falls back to PROD_PASSIVE for passive observation
```

2. View leak reports:
```bash
grep "LeakDetector\|memory leak" logs/lingframe.log
```

3. Generate heap dump for analysis:
```bash
# Trigger heap dump
jmap -dump:format=b,file=heap.hprof <pid>

# Analyze with MAT or VisualVM
```

### 4.2 ClassLoader Leak

**Symptoms:** Metaspace keeps growing after multiple hot updates.

**Common Leak Sources:**

| Leak Source | Diagnosis | Solution |
|-------------|-----------|----------|
| ThreadLocal not cleaned | Check ThreadLocal in ling | Clean up in `onStop()` |
| Static collections | Check static Map/List in ling | Avoid using or clean proactively |
| Callbacks not unregistered | Check event listener registration | Use EventBus auto-cleanup |
| Thread pool not closed | Check thread pools created by ling | Close in `onStop()` |

**Diagnostic Commands:**
```bash
# View ClassLoader count
jcmd <pid> VM.classloaders

# View class statistics
jcmd <pid> GC.class_stats | grep LingClassLoader
```

---

## 5. State Machine Issues

### 5.1 State Transition Failed

**Symptoms:**
```
IllegalStateTransitionException: Cannot transition from READY to LOADING
```

**Cause:** State transition violated state machine rules.

**State Transition Rules:**

```
InstanceStatus:
  CREATED → LOADING → STARTING → READY → STOPPING → DEAD
      ↓         ↓          ↓        ↓         ↓
    ERROR ←───────────────────────────────────
    
RuntimeStatus:
  INACTIVE → ACTIVE ↔ DEGRADED
      ↓         ↓         ↓
  REMOVED ←  STOPPING ←──┘
```

**Diagnosis:**
```bash
grep "IllegalStateTransition\|StateMachine" logs/lingframe.log
```

### 5.2 RuntimeStatus Inconsistent with InstanceStatus

**Symptoms:** Instance is READY but Runtime shows DEGRADED.

**Diagnosis:**
```bash
# View state aggregation logs
grep "RuntimeCoordinator\|reevaluate" logs/lingframe.log
```

**Possible Causes:**

1. Other instances in ERROR state
2. Event publishing delayed
3. CAS conflict caused state not updated

---

## 6. Log Analysis

### 6.1 Log Level Configuration

```yaml
logging:
  level:
    com.lingframe: DEBUG
    com.lingframe.core.fsm: TRACE      # State machine detailed logs
    com.lingframe.core.pipeline: TRACE # Pipeline detailed logs
    com.lingframe.core.classloader: DEBUG  # ClassLoader logs
```

### 6.2 Key Log Keywords

| Scenario | Keywords |
|----------|----------|
| Lifecycle | `LingLifecycleEngine`, `InstanceStateChangedEvent` |
| Invocation chain | `InvocationPipelineEngine`, `Filter`, `invoke` |
| ClassLoader | `LingClassLoader`, `SharedApiClassLoader`, `loadClass` |
| State machine | `StateMachine`, `transition`, `CAS` |
| Governance | `CircuitBreaker`, `RateLimiter`, `Permission` |
| Memory | `LeakDetector`, `evict`, `cleanup` |

### 6.3 Log Analysis Examples

```bash
# View complete lifecycle of a ling
grep "my-ling" logs/lingframe.log | grep -E "Installing|Installed|Starting|READY|STOPPING|DEAD"

# View invocation failure reasons
grep -B 5 "LingInvocationException" logs/lingframe.log

# View state transition chain
grep "state changed" logs/lingframe.log | tail -50
```

---

## 7. Dashboard Diagnostics

### 7.1 Viewing Status via Dashboard

1. **Ling List**: View RuntimeStatus of all lings
2. **Instance Details**: View InstanceStatus, active request count
3. **Governance Panel**: View circuit breaker status, rate limiter status
4. **Event Stream**: Real-time view of runtime events via SSE

### 7.2 Simulation Invocation

Use Dashboard simulation to test governance chain without real side effects:

```
POST /lingframe/dashboard/simulate/lings/{lingId}/ipc
{
  "serviceId": "MyService.doSomething",
  "args": ["param1", "param2"]
}
```

See [Dashboard Documentation](dashboard.md).

---

## 8. Common Error Codes

| Error Code | Meaning | Solution |
|------------|---------|----------|
| `CLASS_LOADER_CLOSED` | ClassLoader closed | Check if ling has been unloaded |
| `INSTANCE_NOT_READY` | Instance not ready | Wait for instance startup to complete |
| `PERMISSION_DENIED` | Permission denied | Check capabilities configuration |
| `CIRCUIT_BREAKER_OPEN` | Circuit breaker open | Wait for recovery or adjust threshold |
| `RATE_LIMITED` | Rate limit triggered | Adjust rate limit config or reduce request frequency |
| `TIMEOUT` | Invocation timeout | Increase timeout or optimize performance |
| `STATE_CONFLICT` | State conflict | Retry operation |

---

## 9. Getting Help

If the above methods cannot resolve your issue:

1. **Submit Issue**: [GitHub Issues](https://github.com/LingFrame/LingFrame/issues)
2. **Provide Information**:
   - LingFrame version
   - JDK version
   - Spring Boot version
   - Complete error logs
   - Steps to reproduce
