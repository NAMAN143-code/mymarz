package com.hotswap.core;

import com.hotswap.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled polling engine that fetches config values from sources
 * and updates {@code @HotSwap} fields when changes are detected.
 *
 * <p>Key design decisions from ADR-001:</p>
 * <ul>
 *   <li>Per-source polling — if 50 fields share one source, only one poll runs</li>
 *   <li>Circuit breaker with exponential backoff on failures</li>
 *   <li>Thread pool sized to min(sources, availableProcessors)</li>
 *   <li>Events fired AFTER AtomicReference.set()</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class ConfigSourcePoller {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourcePoller.class);

    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long MIN_POLL_INTERVAL_MS = 500L;

    private final HotSwapRegistry registry;
    private final TypeCoercer typeCoercer;
    private final ApplicationEventPublisher eventPublisher;
    private final int threadPoolSize;

    private ScheduledExecutorService scheduler;

    /** source URI → cached key-value pairs (last known state) */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /** source URI → consecutive failure count */
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();

    public ConfigSourcePoller(HotSwapRegistry registry,
                              TypeCoercer typeCoercer,
                              ApplicationEventPublisher eventPublisher,
                              int threadPoolSize) {
        this.registry = registry;
        this.typeCoercer = typeCoercer;
        this.eventPublisher = eventPublisher;
        this.threadPoolSize = threadPoolSize;
    }

    /**
     * Start polling all registered sources.
     * Called after Spring context is fully initialized.
     */
    public void start() {
        int sources = registry.getSourceCount();
        if (sources == 0) {
            log.info("No @HotSwap fields registered — poller will not start");
            return;
        }

        int poolSize = Math.min(
                Math.max(threadPoolSize, 1),
                Math.min(sources, Runtime.getRuntime().availableProcessors())
        );

        scheduler = Executors.newScheduledThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "hotswap-poller");
            t.setDaemon(true);
            return t;
        });

        for (String sourceUri : registry.getAllSourceUris()) {
            long interval = Math.max(registry.getMinPollInterval(sourceUri), MIN_POLL_INTERVAL_MS);

            // Skip push-only sources (all fields have pollInterval = -1)
            if (interval <= 0) {
                log.debug("Source '{}' is push-only, skipping poll scheduling", sourceUri);
                continue;
            }

            scheduler.scheduleAtFixedRate(
                    () -> pollSource(sourceUri),
                    interval,  // initial delay = one interval
                    interval,
                    TimeUnit.MILLISECONDS
            );

            log.info("Scheduled poller for source '{}' every {}ms ({} fields)",
                    sourceUri, interval, registry.getFieldsForSource(sourceUri).size());
        }

        log.info("HotSwap poller started — {} source(s), {} field(s), pool size {}",
                sources, registry.getFieldCount(), poolSize);
    }

    /**
     * Poll a single source and update any changed fields.
     */
    void pollSource(String sourceUri) {
        try {
            ConfigSource source = registry.getConfigSource(sourceUri);
            if (source == null) {
                log.warn("No ConfigSource registered for URI: {}", sourceUri);
                return;
            }

            // Circuit breaker check
            int failures = failureCounts.getOrDefault(sourceUri, 0);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                long backoff = calculateBackoff(failures);
                log.debug("Circuit breaker active for '{}' — backoff {}ms", sourceUri, backoff);
                // The scheduler keeps running at its fixed rate, but we skip execution
                // until enough intervals have passed for the backoff
                if (failures % Math.max(1, (int) (backoff / registry.getMinPollInterval(sourceUri))) != 0) {
                    return;
                }
            }

            if (!source.isAvailable()) {
                int newCount = failureCounts.merge(sourceUri, 1, Integer::sum);
                log.warn("Source '{}' unavailable (failure #{}/{})", sourceUri, newCount, MAX_CONSECUTIVE_FAILURES);
                return;
            }

            List<HotSwapFieldHolder> holders = registry.getFieldsForSource(sourceUri);
            Map<String, String> cachedValues = cache.computeIfAbsent(sourceUri, k -> new ConcurrentHashMap<>());

            for (HotSwapFieldHolder holder : holders) {
                try {
                    String rawValue = source.resolve(holder.getKey());

                    if (rawValue == null) {
                        log.trace("Key '{}' not found in source '{}'", holder.getKey(), sourceUri);
                        continue;
                    }

                    String previousRaw = cachedValues.get(holder.getKey());

                    // Only update if the raw value changed
                    if (!Objects.equals(rawValue, previousRaw)) {
                        Object coercedValue = typeCoercer.coerce(rawValue, holder.getType(), holder.getField());
                        Object oldValue = holder.updateValue(coercedValue);

                        cachedValues.put(holder.getKey(), rawValue);

                        log.info("HotSwap: key='{}' changed from {} to {} (source='{}')",
                                holder.getKey(), oldValue, coercedValue, sourceUri);

                        // Fire event AFTER AtomicReference.set() — per ADR-001
                        if (eventPublisher != null) {
                            eventPublisher.publishEvent(
                                    new HotSwapEvent(holder.getBean(), holder.getKey(),
                                            oldValue, coercedValue, sourceUri));
                        }
                    }
                } catch (Exception e) {
                    log.error("Error updating field for key '{}' from source '{}': {}",
                            holder.getKey(), sourceUri, e.getMessage(), e);
                }
            }

            // Reset failure count on successful poll
            failureCounts.remove(sourceUri);

        } catch (Exception e) {
            int newCount = failureCounts.merge(sourceUri, 1, Integer::sum);
            log.error("Error polling source '{}' (failure #{}/{}): {}",
                    sourceUri, newCount, MAX_CONSECUTIVE_FAILURES, e.getMessage(), e);
        }
    }

    /**
     * Calculate exponential backoff: 5s → 10s → 20s → 40s → cap at 60s.
     */
    long calculateBackoff(int failures) {
        long backoff = MIN_POLL_INTERVAL_MS * (long) Math.pow(2, Math.min(failures, 10));
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    /**
     * Graceful shutdown — stop scheduler, clear caches.
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    log.warn("HotSwap poller forced shutdown after 5s timeout");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        cache.clear();
        failureCounts.clear();
        log.info("HotSwap poller shut down");
    }

    /** @return true if the scheduler is running */
    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /** @return failure count for a source (for testing/metrics) */
    public int getFailureCount(String sourceUri) {
        return failureCounts.getOrDefault(sourceUri, 0);
    }
}
