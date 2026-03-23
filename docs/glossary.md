# Glossary

This page is for first-time LingFrame readers.

If the rest of the docs feel dense, start here and come back.

---

## LingFrame

The project as a whole: a JVM runtime governance framework for long-running systems.

## LingCore

The host application that owns the runtime and loads lings.

## Ling

A separately loaded business unit inside the LingCore process.

## Shared API

The process-level public contract boundary between LingCore and lings, or between lings.

Use it for interfaces, DTOs, and contract-level value objects.

## Governance Kernel

The runtime layer that applies governance consistently instead of letting each entry point invent its own logic.

## Invocation Pipeline

The ordered governance path used to process invocation-related decisions.

## `NORMAL`

Run governance and execute the real terminal invocation.

## `SIMULATION`

Run the real governance path without causing real side effects.

## `GOVERN_ONLY`

Run governance but skip terminal execution inside the pipeline.

## `InstanceStatus`

Lifecycle state of one specific ling instance.

## `RuntimeStatus`

Host-visible macro availability state of a ling runtime.

## Dashboard

In `0.3.0`, the dashboard is mainly a governance control surface: REST APIs, SSE stream, simulation, canary operations, metrics, and health views.

## Canary

Routing a portion of traffic to a chosen ling version or instance instead of sending all traffic to the default path.

## Unload Cleanup

The runtime work that happens when a ling is removed: drain requests, evict resources, close classloader-related state, and run leak diagnostics.
