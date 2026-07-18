# Glossary

This document is prepared for developers encountering LingFrame for the first time.

If other documents feel too dense, read through this page first. Returning to the other material will feel remarkably easier.

---

## LingFrame

The overall project name: an order-keeping architecture and JVM runtime governance framework for long-running systems.

## LingCore

The LingCore process: the application side that runs the governance kernel.

## Ling

The isolated business unit that is independently loaded, managed, and governed within the LingCore process.

## Shared API

The process-level shared contract boundary bridging LingCore to Lings, or Lings to Lings.

It is currently utilized to carry:

- Interfaces
- DTOs (Data Transfer Objects)
- Essential annotations
- Essential constants

## Governance Kernel

The core runtime layer responsible for applying unified application governance rules.

## Invocation Pipeline

The strictly ordered primary execution chain running all invocation governance decisions.

## `NORMAL`

Execution mode: exercises all governance components alongside the actual terminal invocation.

## `SIMULATION`

Execution mode: exercises the real governance path logic, but does not produce actual business side-effects.

## `GOVERN_ONLY`

Execution mode: exercises the governance flow, but cuts the Pipeline short, omitting the final terminal invocation entirely.

## `InstanceStatus`

The distinct lifecycle state belonging to a specific Ling instance.

## `RuntimeStatus`

The macro availability state that a Ling as a whole currently exposes from the perspective of LingCore.

## Dashboard

Right now, the Dashboard should be more deeply understood as the runtime governance control surface, rather than merely a frontend webpage.

## Canary

Routing a deliberate subset of incoming traffic to a specific Ling version or instance, instead of dispatching everything to the default path.

## Unload Cleanup

When a Ling is explicitly removed, the runtime will drain requests, evict resources, clear classloader-linked states, and finally execute passive leak diagnostics.
