# MARZ

[![Build](https://github.com/NAMAN143-code/hswap/actions/workflows/ci.yml/badge.svg)](https://github.com/NAMAN143-code/hswap/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

**Runtime configuration management for Java — change config values without restarting your application.**

MARZ is a Spring annotation library that enables true JVM-level hot-swapping of configuration values. Annotate a field with `@Marz`, point it at a config file, and the value updates in memory the instant the file changes. No restart. No refresh endpoint. No SDK calls. Just an annotation.

## How It's Different

| Approach | Mechanism | Restart Required? | Granularity |
|----------|-----------|-------------------|-------------|
| Spring `@RefreshScope` | Recreates entire bean | Actuator `/refresh` call | Bean-level |
| LaunchDarkly / Unleash | SDK method calls | No | Per-evaluation |
| Spring Cloud Config | Properties reload | Yes (or actuator) | Application-level |
| **MARZ** | **`AtomicReference` swap via OS file events** | **No** | **Field-level** |

MARZ uses OS-level file change detection (`WatchService` — inotify on Linux, kqueue on macOS) combined with a reverse index that maps config keys directly to in-memory `AtomicReference` locations. When a config file changes, only the specific fields bound to changed keys are updated. Everything else is untouched.

**Read path cost:** ~5ns (volatile read). Zero IO. Zero network calls.

## Quick Start (5 minutes)

### 1. Add the dependency

```xml
<dependency>
    <groupId>com.mymarz</groupId>
    <artifactId>marz-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Create a config file

```yaml
# config/marz.yml
feature:
  new-checkout:
    enabled: true
rate:
  limit:
    max-requests: 200
```

### 3. Annotate your fields

```java
@Service
public class CheckoutService {

    @Marz(key = "feature.new-checkout.enabled", source = "file://config/marz.yml")
    private boolean newCheckoutEnabled = false;

    @Marz(key = "rate.limit.max-requests", source = "file://config/marz.yml")
    private int maxRequests = 100;

    public void processOrder(Order order) {
        if (newCheckoutEnabled) {
            // new checkout flow — toggle this by editing the YAML
        }
    }
}
```

### 4. Run your app and change the YAML

Edit `config/marz.yml` while your app is running. The field value updates within milliseconds — no restart, no refresh endpoint, no downtime.

## Architecture: Event-Driven Targeted Swap

MARZ does **not** poll your config files on a timer. Instead:

```
Config File Changes
        │
        ▼
WatchService (OS kernel: inotify/kqueue)
        │  ← BLOCKS until file actually changes. Zero CPU when idle.
        ▼
Read file → Diff against cached state
        │
        ▼
Reverse Index lookup (key → List<FieldBinding>)
        │  ← Only changed keys are looked up. O(1).
        ▼
AtomicReference.set(newValue)
        │  ← Only affected fields are touched.
        ▼
MarzEvent published (Spring ApplicationEvent)
```

If your YAML has 200 keys and you change 1, only that 1 key's bound fields are swapped. The other 199 are never read, parsed, or touched.

### IO Profile

| Scenario | IO | CPU |
|----------|-----|-----|
| File source, nothing changes | **Zero** | **Zero** (blocked on `WatchService.take()`) |
| File source, 1 key changes | 1 file read (~0.1ms) | ~0.5ms |
| App reads `@Marz` field | **Zero** | **~5ns** (volatile read) |

### Tiered Source Resolution

MARZ automatically selects the best change detection strategy for your environment:

| Mode | Detection | When |
|------|-----------|------|
| **A: WatchService** | OS inotify/kqueue | Local files, Docker volumes, K8s ConfigMaps |
| **B: Platform Push** | WebSocket | `source="platform://marz"` (commercial) |
| **C: Platform Promoted** | WebSocket (upgraded) | NFS/network FS + platform connected |
| **D: CRC32 Poll** | Periodic checksum | Last resort (no WatchService, no platform) |

No configuration needed — `SourceStrategyResolver` probes the filesystem at startup and picks the optimal mode.

## Annotation Reference

```java
@Marz(
    key = "feature.dark-mode.enabled",      // Required: config key to resolve
    source = "file://config/marz.yml",      // Config source URI (default: platform://marz)
    safetyNetInterval = 60000,              // Safety-net CRC32 check interval in ms (default: 60000)
    defaultValue = "false",                 // Fallback when source is unreachable
    type = MarzType.INFERRED,               // Type coercion (auto-detected from field)
    description = "Enable dark mode",        // Human-readable label for dashboard
    requiresApproval = false,               // Require approval workflow via platform
    sensitive = false                       // Mask value in logs and dashboard
)
private volatile boolean darkModeEnabled;   // Fields MUST be volatile
```

### Supported Types

`boolean`, `String`, `int`, `long`, `double`, and JSON objects (deserialized via Jackson).

### Supported Config Sources

| Source | URI Scheme | Example |
|--------|-----------|---------|
| Local file (YAML) | `file://` | `file:///etc/app/config.yml` |
| Local file (JSON) | `file://` | `file://config/flags.json` |
| Local file (Properties) | `file://` | `file://config/app.properties` |
| Classpath | `classpath:` | `classpath:config.properties` |
| HTTP endpoint | `http://` / `https://` | `https://config-server/api/v1/config` |
| MARZ Platform | `platform://marz` | Connects to commercial SaaS dashboard |

## Listening for Changes

```java
@EventListener
public void onConfigChange(MarzEvent event) {
    log.info("{}: {} → {}", event.getKey(), event.getOldValue(), event.getNewValue());
}
```

`MarzEvent` fires **after** the `AtomicReference` is updated, so listeners always see the new state.

## Configuration

```yaml
# application.yml
marz:
  enabled: true                    # Master switch (default: true)
  default-poll-interval: 5000      # Safety-net poll interval in ms
  thread-pool-size: 2              # WatchService thread pool size
  metrics-enabled: true            # Expose Micrometer metrics
```

## Thread Safety

Every `@Marz` field is backed by an `AtomicReference`. Reads are lock-free volatile reads (~5ns). Writes use `compareAndSet` for safe concurrent updates. No `synchronized` blocks, no locks, no contention.

## Core Components

| Component | Responsibility |
|-----------|---------------|
| `@Marz` | Field-level annotation declaring config binding |
| `MarzRegistry` | Reverse index: config key → `List<FieldBinding>` |
| `FieldBinding` | Immutable record: bean + `AtomicReference` + type + key + source |
| `FileConfigSource` | WatchService-based file change detection + diff |
| `TypeCoercer` | String → target type conversion with validation |
| `MARZBeanPostProcessor` | Spring lifecycle hook that scans and registers fields |
| `MARZAutoConfiguration` | Spring Boot auto-configuration entry point |
| `ConfigFormatParser` | SPI for YAML, JSON, and properties file parsing |

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

## Project Status

The open-source annotation library is implemented and functional. A commercial SaaS platform (web dashboard, RBAC, audit logging, multi-instance management) is in development.

**Architecture documentation:** [Confluence — Software Development space](https://n-solution.atlassian.net/wiki/spaces/SD/overview)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

[Apache License 2.0](LICENSE)

---

Built by [Naman Sharma](https://github.com/NAMAN143-code) for Java teams who are tired of restarting apps to change a config value.
