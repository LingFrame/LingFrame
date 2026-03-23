# Infrastructure Development Guide

This guide explains the infrastructure layer from a practical `0.3.0` perspective.

> infrastructure modules are not ordinary feature lings, they are governance-aware proxy paths around shared capabilities such as storage and cache.

---

## Why The Infrastructure Layer Exists

The infrastructure layer lets the runtime:

- centralize capability access
- enforce permissions close to the operation point
- emit audit evidence
- keep business code relatively unaware of proxy mechanics

---

## What Is Actually Implemented Today

In the public `0.3.0` codebase, the clearest implemented paths are:

- `lingframe-infra-storage`
- `lingframe-infra-cache`

---

## Storage Proxy Path

The storage module wraps JDBC access so SQL operations can be observed and governed near execution.

Key pieces include:

- `DataSourceWrapperProcessor`
- `LingDataSourceProxy`
- `LingConnectionProxy`
- `LingStatementProxy`
- `LingPreparedStatementProxy`

Capability example:

- `storage:sql`

---

## Cache Proxy Path

The cache module governs local cache and Redis-oriented access paths.

Key pieces include:

- `SpringCacheWrapperProcessor`
- `LingCacheManagerProxy`
- `LingSpringCacheProxy`
- `RedisPermissionInterceptor`

Capability examples:

- `cache:local`
- `cache:redis`

---

## How To Think About Capability IDs

Capability IDs should be:

- stable
- explicit
- close to the real underlying capability

Examples:

- `storage:sql`
- `cache:local`
- `cache:redis`

Business lings declare what they need in `ling.yml`.

---

## When To Build A New Infrastructure Proxy

Create a new infrastructure proxy path when:

- the capability is shared by more than one ling
- access should be governed consistently
- permission and audit need to happen near the real operation

---

## Minimal Extension Pattern

Most infrastructure extensions follow the same broad shape:

1. wrap or intercept the underlying capability
2. derive an access type such as `READ`, `WRITE`, or `EXECUTE`
3. ask the permission service
4. emit audit evidence
5. continue or reject

---

## Best Practices

- keep the proxy transparent to business users
- put the interception point as close as possible to the real operation
- keep capability naming consistent
- avoid blocking business flow with expensive audit work when an async path exists
- document what is truly implemented versus still aspirational

Continue with [Ling Development Guide](ling-development.md) if you want the business-ling side, or [Shared API Guidelines](shared-api-guidelines.md) if you want the contract side.
