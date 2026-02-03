# LingFrame · 灵珑

![Status](https://img.shields.io/badge/Status-Core_Implemented-green)
![License](https://img.shields.io/badge/License-Apache_2.0-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.6-brightgreen)
[![Help Wanted](https://img.shields.io/badge/PRs-welcome-brightgreen)](../../pulls)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/LingFrame/LingFrame)

[中文版 / Chinese](./README.zh-CN.md)

## Start From Here

- **Technical Entry**: Dive into governance details and architecture 👉 [technical-entry.md](docs/technical-entry.md)
- **Practical Entry**: Quick start and canary deployment 👉 [practical-entry.md](docs/practical-entry.md)
- **Quick Trial**: 👉 [getting-started.md](docs/getting-started.md)
- **Core Stance**: 👉 [MANIFESTO.md](MANIFESTO.md)
- **Design Principles and Boundaries**: 👉 [WHY.md](WHY.md)

You don't need to read everything at once.  
LingFrame allows you to pause at any stage.

---

![LingFrame Dashboard Example](./docs/images/dashboard.png)

*Real-time plugin governance dashboard: monitor status, canary traffic, and audit logs.*

---

LingFrame (LingFrame) is a **JVM runtime governance framework for long-running systems**.  
It aims to keep legacy monolithic applications stable, controllable, and evolvable **without rewriting the system or forcing microservices splits**.

Many systems aren't poorly designed—  
they've just lived too long and changed too hastily.

---

## Prologue

It wasn't born for elegance initially.

One day, people realized the system had grown too vast to comprehend, yet it couldn't stop.  
Every change felt like groping in the dark,  
every deployment came with a prayer.

So, someone asked a seemingly conservative question:

> If the system can't be rewritten for now,  
> can it still be **governed**?

Not through more rules,  
but through **clearer boundaries**.  
Not making decisions for the system,  
but putting things back in their rightful place while it's still understandable.

Thus, LingFrame was born.

---

## What LingFrame Focuses On Isn't "Adding Features"

In many real-world systems, the issue isn't a lack of features, but:

- The system is still running, but no one dares to change it
- Module boundaries fade, couplings become untraceable
- After introducing plugins, isolation stays structural only
- Restarts aren't unacceptable, but **unpredictable**

LingFrame addresses one core problem:

> **How to prevent systems from losing control in long-term operation.**

---

## Current Stage

**v0.1.x · Maiden Phase (Preview)**

This is a stage where the direction is frozen and boundaries are forming:

- Not pursuing full features
- No backward compatibility promises
- Verifying one thing:  
  **Does runtime governance hold in a single process?**

This is a phase that rejects pandering and begins choices.

---

## What Is LingFrame

- A **JVM runtime governance framework**
- A **structural tool for legacy systems**
- A **system that allows plugins but doesn't tolerate their chaos**

It's not a microservices replacement,  
nor a modularization silver bullet.

LingFrame's purpose is to provide possibilities for **"retraction" and "reorganization"** when the system reaches a certain complexity.

---

## Technical Boundaries (Overview)

- JVM: JDK 21 / JDK 8 (future compatibility support)
- Spring Boot: 3.x / 2.x (future compatibility support)
- Single-process plugin isolation and governance
- Clear distinction: **Interface stability ≠ Implementation stability**

LingFrame doesn't hide complexity—  
it just refuses to dump it all on the user at once.

---

## Finally

LingFrame won't make decisions for the system.

She just helps put things back in place while the system is still willing to be understood.

If you just stop here,  
that's perfectly fine.