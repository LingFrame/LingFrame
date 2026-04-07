# Infrastructure Development Guide

This guide is based on real code and explains how to understand the infrastructure layer.

> Infrastructure modules are not ordinary business lings. They are proxy paths that provide governance awareness around shared capabilities, like storage and caching.

---

## Why Is an Infrastructure Layer Needed?

The point of the infrastructure layer is to allow the runtime to:

- Centralize capability entry points.
- Perform permission control as close to the actual operation as possible.
- Generate auditing evidence.
- Keep business code mostly oblivious to the proxy details.

## What Has Already Been Implemented?

The clearest implementation paths currently are:

- `lingframe-infra-storage`
- `lingframe-infra-cache`

---

## Storage Proxy Path

The storage module leverages JDBC wrapping to ensure SQL operations are observed and governed near the point of execution.

Key components include:

- `DataSourceWrapperProcessor`
- `LingDataSourceProxy`
- `LingConnectionProxy`
- `LingStatementProxy`
- `LingPreparedStatementProxy`

Typical capability:

- `storage:sql`

---

## Cache Proxy Path

The caching module is responsible for governing local caches and Redis-related access paths.

Key components include:

- `SpringCacheWrapperProcessor`
- `LingCacheManagerProxy`
- `LingSpringCacheProxy`
- `RedisPermissionInterceptor`

Typical capabilities:

- `cache:local`
- `cache:redis`

---

## How to Understand Capabilities

Capability identifiers should be:

- Stable
- Unambiguous
- As close to the real underlying capability as possible

Common identifiers in the current codebase:

- `storage:sql`
- `cache:local`
- `cache:redis`

---

## When Is It Worth Creating a New Infrastructure Proxy?

It is only worth adding a new proxy path if the following conditions hold true:

- The capability will be shared across multiple lings.
- The capability requires unified governance.
- Permissions and auditing should be closely tied to where the true operation happens.

---

## Minimal Extension Pattern

Most infrastructure extensions follow the same structure:

1. Wrap or intercept the entry point to the underlying capability.
2. Infer the access type (`READ` / `WRITE` / `EXECUTE`, etc.).
3. Call the permission service.
4. Produce auditing evidence.
5. Continue execution or reject immediately.

---

## Best Practices

- Keep the proxy transparent to business consumers.
- Place interception points as close to the actual operation as possible.
- Maintain consistent capability naming.
- If using an asynchronous auditing path, ensure auditing does not block the main business flow.
- Clearly distinguish between "already implemented paths" and "future directions."

If you need to return to the business ling side, read the [Business Ling Development Guide](ling-development.md);
If you need to review contract boundaries, read the [Shared API Guidelines](shared-api-guidelines.md).
