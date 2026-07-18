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

---

## 4. Service Playground

- Default is **real invoke** (API verification).
- UI can switch to **simulation** (governance chain only).
- Always pair with a strong Dashboard token; do not expose Dashboard on untrusted networks.

---

## 5. Status operations

| Action | Meaning |
| --- | --- |
| **Soft stop** (runtime → `INACTIVE`) | Stop new traffic / revoke enable; **instances stay in-process** |
| **Unload / REMOVED** | Drain + tearDown + resource cleanup |

Soft-stop API: `POST /lingframe/dashboard/lings/{lingId}/soft-stop` (optional body `{"version":"..."}`).  
Do not treat soft stop as “fully unloaded”.

---

## 6. Build matrix

- Primary path: Spring Boot 2.7 + JDK 8 (default profile)
- Dual stack: `-Pspring-boot3` + JDK 17
- 0.4 label: **design-debt convergence → candidate kernel**, not “certified production without further hardening”

---

## 7. Minimum checklist

- [ ] `dev-mode: false`
- [ ] Dashboard `allow-weak: false` + strong token
- [ ] Enable `ling-core-governance` as needed
- [ ] `security.strict-mode: true` (unless audited exemptions)
- [ ] Decide `force-drain-on-timeout` and drain timeout
- [ ] Shared API changes via restart / versioned packages
- [ ] Never promote example `123456` + open dev bypass to production
