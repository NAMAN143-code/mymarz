# HotSwap 🔥

**Runtime configuration hot-swap for Spring Boot** — change feature flags, toggles, and config values without restarting your JVM.

[![CI](https://github.com/NAMAN143-code/hswap/actions/workflows/ci.yml/badge.svg)](https://github.com/NAMAN143-code/hswap/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

## What is HotSwap?

HotSwap is a Spring Boot annotation that polls an external config source and updates variable state **in the running JVM**. The next time your code reads the variable, it sees the new value. No restart. No redeployment. No downtime.

```java
@Service
public class CheckoutService {

    @HotSwap(key = "feature.new-checkout.enabled")
    private boolean newCheckoutEnabled = false;

    @HotSwap(key = "rate.limit.max-requests", source = "file:///etc/myapp/config.yml")
    private int maxRequests = 100;

    public void processOrder(Order order) {
        if (newCheckoutEnabled) {
            // New checkout flow — toggled at runtime!
        }
    }
}
```

## Quick Start (5 minutes)

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.hotswap</groupId>
    <artifactId>hotswap-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Create a config file

```yaml
# config/hotswap.yml
feature:
  new-checkout:
    enabled: true
rate:
  limit:
    max-requests: 200
```

### 3. Annotate your fields

```java
@HotSwap(key = "feature.new-checkout.enabled", source = "file://config/hotswap.yml")
private boolean newCheckoutEnabled = false;
```

### 4. Run your app and change the YAML

Edit `config/hotswap.yml` while your app is running. Within 5 seconds (default poll interval), the field value updates. No restart needed.

## Supported Config Sources

| Source | URI Scheme | Example |
|--------|-----------|---------|
| Local file | `file://` | `file:///etc/app/config.yml` |
| Classpath | `classpath:` | `classpath:config.properties` |
| HTTP endpoint | `http://` / `https://` | `https://config-server/api/v1/config` |
| HotSwap Platform | `platform://hotswap` | (default — connects to commercial SaaS) |

## Supported Types

`boolean`, `String`, `int`, `long`, `double`, and JSON (deserialized via Jackson).

## How It Works

1. Spring `BeanPostProcessor` scans for `@HotSwap` annotations at startup
2. Each field is backed by a thread-safe `AtomicReference`
3. A `ScheduledExecutorService` polls the config source at the configured interval
4. When a value changes, the `AtomicReference` is updated atomically
5. A `HotSwapEvent` is published via Spring's event system

```java
@EventListener
public void onConfigChange(HotSwapEvent event) {
    log.info("Config changed: {} -> {} -> {}", event.getKey(), event.getOldValue(), event.getNewValue());
}
```

## Configuration

```yaml
# application.yml
hotswap:
  enabled: true
  default-poll-interval: 5000
  thread-pool-size: 2
  metrics-enabled: true
```

## Requirements

- Java 17+
- Spring Boot 3.x
- Spring Framework 6.x

## Building from Source

```bash
git clone https://github.com/NAMAN143-code/hswap.git
cd hswap
mvn clean install
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

[Apache License 2.0](LICENSE)

## About

Built by [Naman Sharma](https://github.com/NAMAN143-code) for Java teams who are tired of restarting apps to change a config value.
