# Infrastructure Development Guide

This guide explains the infrastructure layer from the current real codebase.

> Infrastructure modules are not ordinary business lings. They are governance-aware proxy paths around shared capabilities such as storage and cache.

---

## Why The Infrastructure Layer Exists

The infrastructure layer exists so that the runtime can:

- centralize capability entry points
- apply permission control close to the real operation point
- produce audit evidence
- keep business code mostly unaware of proxy details

## What Is Already Implemented

The clearest current implementation paths are:

- `lingframe-infra-storage`
- `lingframe-infra-cache`

---

## Storage Proxy Path

The storage module wraps JDBC paths so that SQL operations are observed and governed close to execution.

Key building blocks include:

- `DataSourceWrapperProcessor`
- `LingDataSourceProxy`
- `LingConnectionProxy`
- `LingStatementProxy`
- `LingPreparedStatementProxy`

Typical capability:

- `storage:sql`

---

## Cache Proxy Path

The cache module governs both local-cache and Redis-related access paths.

Key building blocks include:

- `SpringCacheWrapperProcessor`
- `LingCacheManagerProxy`
- `LingSpringCacheProxy`
- `RedisPermissionInterceptor`

Typical capabilities:

- `cache:local`
- `cache:redis`

---

## How To Think About Capability

A capability identifier should be:

- stable
- explicit
- as close as possible to the real underlying operation

Common current identifiers include:

- `storage:sql`
- `cache:local`
- `cache:redis`

---

## When It Is Worth Adding A New Infrastructure Proxy

A new proxy path is only worth adding when:

- the capability is shared by multiple lings
- the capability needs unified governance
- permission and audit should stay close to the real operation point

---

## Minimal Extension Pattern

Most infrastructure extensions follow the same structure:

1. wrap or intercept the low-level capability entry
2. infer `READ` / `WRITE` / `EXECUTE` style access type
3. call the permission service
4. emit audit evidence
5. continue or reject

---

## Best Practices

- keep the proxy transparent to business code
- keep interception close to the real operation
- keep capability naming consistent
- if audit is asynchronous, do not let it block the main business flow
- distinguish clearly between “already implemented paths” and “future ideas”

If you want to move back to the business-ling side, read [Business Ling Development Guide](ling-development.md).  
If you want to focus on contract boundaries, read [Shared API Guidelines](shared-api-guidelines.md).
