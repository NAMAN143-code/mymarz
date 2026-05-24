# MARZ Demo Application

A Spring Boot app that demonstrates `@Marz` annotation — change config values at runtime without restarting.

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
