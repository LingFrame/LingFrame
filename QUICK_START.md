# LingFrame Quick Start

This page does one thing only: **get LingFrame running through the shortest possible path**.

If you only want to know whether the sample boots, the dashboard opens, and a real request works, this is enough.  
For full onboarding, continue to `docs/getting-started.md`.

## 1. Requirements

- JDK 17+ recommended
- Maven 3.8+

## 2. Build the sample

From the repository root:

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am package -DskipTests
```

## 3. Start the sample app

```powershell
cd .\lingframe-examples\lingframe-example-lingcore-app
mvn spring-boot:run
```

Default entry points:

- App and APIs: `http://localhost:8888`
- Dashboard: `http://localhost:8888/dashboard.html`

## 4. Check that lings are loaded

```powershell
curl http://localhost:8888/lingframe/dashboard/lings
```

You should normally see:

- `order-ling`
- `user-ling`

## 5. Send one real request

```powershell
curl http://localhost:8888/user-ling/user/listUsers
```

If it returns normally, the minimum runtime path is working:

- LingCore booted
- lings were loaded
- web routing is alive
- dashboard is usable

## 6. Optional: run one verified regression

Observability closed loop:

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am "-Pspring-boot2,integration-check" verify "-Dit.test=ObservabilityClosedLoopIntegrationTest"
```

Browser-level dashboard smoke:

```powershell
mvn -pl lingframe-examples/lingframe-example-lingcore-app -am "-Pspring-boot2,integration-check" verify "-Dit.test=DashboardUiSmokeIntegrationTest"
```

## 7. What to read next

- Full onboarding: `docs/getting-started.md`
- Dashboard docs: `docs/dashboard.md`
- Project overview: `README.md`
