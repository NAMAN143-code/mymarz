# MARZ Demo Application

A Spring Boot app that demonstrates the `@Marz` annotation — change config values
at runtime without restarting — **and proves the performance/IO win** with a
built-in benchmark, IO metrics, and a JUnit performance test.

Every stage of the demo narrates itself in the console with numbered `STEP`
banners, so you can watch exactly what MARZ does at startup, on each read, and
on every live config change.

## TL;DR — the performance story

`@Marz` reads are plain `volatile` field reads: **~30 ns, zero file IO, zero
reflection**. The naive "always-fresh config" alternative re-opens and re-parses
the YAML file on every access (~tens of microseconds + a syscall + a parse every
time). The demo races them head-to-head:

```
GET /perf?iterations=500000
  marz : 32 ns/op, 31,250,000 ops/sec     (0 file reads)
  naive: 81,551 ns/op, 12,262 ops/sec     (500,000 file reads, ~1 GB read)
  → MARZ ~2500× faster, 100% of read-path IO eliminated
```
(Exact numbers vary by machine; the test asserts a conservative ≥5× floor.)

## Prerequisites

- Java 17+
- Maven 3.9+
- The `marz-spring-boot-starter` installed locally (`mvn install` from the parent directory)

## Quick Start

```bash
# 1. Install the MARZ library locally
cd ..
mvn clean install -DskipTests

# 2. Run the demo app
cd marz-demo
mvn spring-boot:run
```

## Try It

### Step 1: See current config
```bash
curl localhost:8080/config
```
```json
{
  "feature.new-checkout.enabled": false,
  "feature.dark-mode.enabled": false,
  "rate-limit.max-requests-per-minute": 100,
  "app.welcome-message": "Welcome to MARZ Demo!",
  "app.discount-rate": 0.0,
  "secrets.api-key": "***"
}
```

### Step 2: Edit config while the app is running
Open `config/marz-demo.yml` in your editor and change:
```yaml
feature:
  new-checkout:
    enabled: true          # was: false
app:
  discount-rate: 0.15      # was: 0.0
  welcome-message: "Hello from MARZ!"
```
Save the file.

### Step 3: See the change — no restart!
```bash
curl localhost:8080/config
```
```json
{
  "feature.new-checkout.enabled": true,
  "app.discount-rate": 0.15,
  "app.welcome-message": "Hello from MARZ!",
  ...
}
```

### Step 4: Try the checkout endpoint
```bash
curl localhost:8080/checkout
```
```json
{
  "flow": "NEW checkout (v2)",
  "discount": 0.15,
  "message": "Using the new checkout experience!"
}
```

### Step 5: Watch the console
You'll see real-time log output like:
```
CONFIG CHANGED: [feature.new-checkout.enabled] false -> true (source: file:config/marz-demo.yml)
CONFIG CHANGED: [app.discount-rate] 0.0 -> 0.15 (source: file:config/marz-demo.yml)
```

## Performance & IO Metrics

The demo ships a head-to-head benchmark and live metrics endpoints.

### Run the benchmark
```bash
curl 'localhost:8080/perf?iterations=1000000'
```
```json
{
  "iterations": 1000000,
  "marz":  { "nanosPerOp": 30,    "opsPerSecond": 33333333 },
  "naive": { "nanosPerOp": 81551, "opsPerSecond": 12262 },
  "marzSpeedupFactor": 2548.47,
  "io": {
    "naiveFileReads": 1000000,
    "naiveBytesRead": 2033000000,
    "marzFieldReads": 1000000,
    "marzFileReadsOnChange": 0,
    "ioReductionPercent": 100.0
  }
}
```

### IO counters
```bash
curl localhost:8080/metrics/io      # file reads, bytes, parses per strategy
curl localhost:8080/metrics/perf    # registry stats + current @Marz state
```

### Standard actuator surface
The same numbers are bound as Micrometer gauges and the MARZ health indicator is
live:
```bash
curl localhost:8080/actuator/metrics/marz.io.naive.file-reads
curl localhost:8080/actuator/metrics/marz.io.reduction-percent
curl localhost:8080/actuator/metrics/marz.registry.keys
curl localhost:8080/actuator/health
```

### The performance test scenario
`MarzPerformanceTest` boots the full Spring context (so every `@Marz` field is
registered) and asserts the win in CI:

```bash
mvn test
```
It verifies that the MARZ read path is faster, sustains higher throughput, does
**zero** file IO (vs one read+parse per access for the naive path), and that a
runtime hot-swap is visible to readers with no restart. Thresholds are
conservative (≥5×) so it stays green on slow CI boxes.

## What's Demonstrated

| Feature | Annotation | Config Key |
|---------|-----------|------------|
| Boolean feature flag | `@Marz(key = "feature.new-checkout.enabled")` | `feature.new-checkout.enabled` |
| Integer rate limit | `@Marz(key = "rate-limit.max-requests-per-minute")` | `rate-limit.max-requests-per-minute` |
| String config | `@Marz(key = "app.welcome-message")` | `app.welcome-message` |
| Double value | `@Marz(key = "app.discount-rate", type = DOUBLE)` | `app.discount-rate` |
| Sensitive masking | `@Marz(key = "secrets.api-key", sensitive = true)` | `secrets.api-key` |
| Event listener | `@EventListener(MarzEvent.class)` | All keys |

## How It Works

1. `DemoService` annotates fields with `@Marz(source = "file://config/marz-demo.yml")`
2. On startup, MARZ's `BeanPostProcessor` registers each field in the reverse index
3. A `WatchService` thread monitors `config/` for file changes (zero CPU when idle)
4. When you edit `marz-demo.yml`, the WatchService fires
5. Only the changed keys are diffed and swapped via `AtomicReference` — ~5ns per swap
6. `DemoController` reads the field values normally — they're always current

## Components

| Class | Role |
|-------|------|
| `DemoService` | Declares the `@Marz` fields (the fast, zero-IO read path) |
| `NaiveConfigService` | Baseline that re-reads + re-parses the YAML on every access (the "before MARZ" world) |
| `BenchmarkService` | Races MARZ vs naive (warmup + measured) and reports latency/throughput/IO |
| `IoMetrics` | Process-wide IO counters for both strategies |
| `MarzMetricsBinder` | Publishes the counters as Micrometer gauges (`marz.*`) |
| `PerformanceController` | `/perf`, `/metrics/io`, `/metrics/perf` endpoints |
| `StartupNarrator` | Logs registrations + a self-benchmark on boot |
| `MarzEventLogger` | Narrates each live config change step-by-step |
| `StepLogger` | The numbered `STEP` banner helper used everywhere |
| `MarzPerformanceTest` | JUnit 5 performance-test scenario (asserts the win) |
