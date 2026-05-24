package com.mymarz.core;

/**
 * Service Provider Interface for configuration sources.
 *
 * <p>Implement this interface and register via Java SPI ({@code META-INF/services})
 * or as a Spring {@code @Bean} to add custom config backends (e.g., Consul, Vault, etcd).</p>
 *
 * <p>Sources that require background threads (file watchers, HTTP pollers) should
 * override {@link #start()} and {@link #stop()}. Default implementations are no-ops
 * for sources that don't need lifecycle management (e.g., simple classpath readers).</p>
 *
 * @since 1.0.0
 */
public interface ConfigSource {

    /** Resolve a configuration value by key. */
    String resolve(String key);

    /** Health check — is this source currently reachable? */
    boolean isAvailable();

    /** Unique identifier for this source instance. */
    String sourceId();

    /** URI scheme this source handles (e.g., "file", "http"). */
    String scheme();

    /** Start background change detection (file watcher, HTTP poller). Default: no-op. */
    default void start() {}

    /** Stop background change detection and release resources. Default: no-op. */
    default void stop() {}

    /** Whether background change detection is running. Default: false. */
    default boolean isRunning() { return false; }
}
