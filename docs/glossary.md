# Glossary

This page is for first-time LingFrame readers.

If the rest of the docs feel dense, start here and then come back.

---

## LingFrame

The project as a whole: a JVM runtime-governance framework for long-running systems.

## LingCore

`LingCore`, the host application.

## Ling

`Ling`, a business unit that is independently loaded, isolated, and governed inside the LingCore process.

## Shared API

The process-level public contract boundary between LingCore and lings, or between lings.

It is currently used for:

- interfaces
- DTOs
- essential annotations
- essential constants

## Governance Kernel

The runtime core layer that applies governance consistently.

## Invocation Pipeline

The ordered main path that handles invocation-governance decisions.

## `NORMAL`

Run governance and execute the real terminal invocation.

## `SIMULATION`

Run the real governance path without producing real business side effects.

## `GOVERN_ONLY`

Run governance but skip terminal execution inside the pipeline.

## `InstanceStatus`

Lifecycle state of one concrete ling instance.

## `RuntimeStatus`

Macro availability state of a ling runtime from the LingCore point of view.

## Dashboard

Dashboard should currently be understood as a governance control surface, not just a page.

## Canary

Route a portion of traffic to a selected ling version or instance instead of sending all traffic through the default path.

## Unload Cleanup

The runtime work that happens when a ling is removed: draining requests, evicting resources, clearing classloader-related state, and running leak diagnostics.
