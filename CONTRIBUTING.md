# Contributing Guide

Thank you for your interest in LingFrame!

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.8+
- IDE: IntelliJ IDEA (Recommended)

### Build Project

```bash
git clone https://github.com/lingframe/lingframe.git
cd lingframe
mvn clean install -DskipTests
```

### Run Example

```bash
cd lingframe-examples/lingframe-example-host-app
mvn spring-boot:run
```

---

## Architecture Must-Read

> ⚠️ **Please understand these principles, otherwise your PR might be rejected.**

### Core Principles

| Principle | Description |
|-----------|-------------|
| **Zero Trust** | Business modules cannot access DB/Redis directly, must go through Core proxy. |
| **Microkernel** | Core only handles scheduling and arbitration, no business logic. |
| **Contract First** | All interactions via `lingframe-api` interfaces. |
| **Ecosystem Agnostic** | Core is pure Java, does not depend on Spring/ORM. |

### Module Responsibilities

| Module | Responsibility | Dependency Rule |
|--------|----------------|-----------------|
| `lingframe-api` | Contract Layer | No external dependencies. |
| `lingframe-core` | Governance Kernel | **FORBIDDEN** to depend on Spring. |
| `lingframe-runtime` | Ecosystem Adapter | Bridges Core and Spring. |
| `lingframe-dashboard` | Visualization | Depends on Spring Web. |

### Design Principles

- **SRP**: Single Responsibility Principle.
- **DIP**: Dependency Inversion Principle.
- **OCP**: Open/Closed Principle.

---

## Where to Start?

### Good First Issues

Look for these labels in [Issues](../../issues):

| Label | Suitable For |
|-------|--------------|
| `good first issue` | First-time contributors |
| `help wanted` | Need help |
| `documentation` | Documentation improvements |

### Areas Needing Help

- ⏳ Unit Test Coverage
- ⏳ Message Proxy (Kafka/RabbitMQ)
- ⏳ Documentation Polish

---

## Contribution Flow

### 1. Pick a Task

Comment on an Issue: "I would like to work on this."

### 2. Develop

```bash
# Clone after Fork
git clone https://github.com/YOUR_USERNAME/lingframe.git

# Create Branch
git checkout -b feature/your-feature

# Develop and Commit
git commit -m "feat: add your feature"
git push origin feature/your-feature
```

### 3. Submit PR

- [ ] Build Passes: `mvn clean compile`
- [ ] Tests Pass: `mvn test`
- [ ] Describe changes clearly

---

## Code Guidelines

### Naming Conventions

| Type | Rule | Example |
|------|------|---------|
| Interface | Descriptive Name | `PluginContext` |
| Implementation | `Default` Prefix | `DefaultPermissionService` |
| Proxy | `Proxy` Suffix | `SmartServiceProxy` |
| Factory | `Factory` Suffix | `SpringContainerFactory` |

### Code Style

- 4 spaces indentation
- Javadoc for classes and methods
- Use Lombok to reduce boilerplate
- Use SLF4J for logging

### Test Requirements

- Core logic must have unit tests
- Test class naming: `XxxTest.java`
- Use JUnit 5 + Mockito

### Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add permission check for SQL execution
fix: fix classloader memory leak on plugin unload
docs: update quick start guide
```

---

## Feedback

| Type | Channel |
|------|---------|
| Bug | [Issues](../../issues) |
| Feature Request | [Discussions](../../discussions) |
| Security | Message Maintainers |

---

## Code of Conduct

- Respect every contributor.
- Be friendly and professional.
- Accept constructive criticism.

Thank you for your contribution! 🎉
