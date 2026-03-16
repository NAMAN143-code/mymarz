package com.hotswap.core;

/**
 * Service Provider Interface for configuration sources.
 *
 * <p>Implement this interface and register via Java SPI ({@code META-INF/services})
 * or as a Spring {@code @Bean} to add custom config backends (e.g., Consul, Vault, etcd).</p>
 *
 * @since 1.0.0
 */
public interface ConfigSource {

    /**
     * Resolve a configuration value by key.
     *
     * @param key dot-notation config key (e.g., "feature.dark-mode.enabled")
     * @return the raw string value, or {@code null} if not found
     */
    String resolve(String key);

    /**
     * Health check — is this source currently reachable?
     *
     * @return {@code true} if source is available
     */
    boolean isAvailable();

    /**
     * Unique identifier for this source instance (used in logging, metrics, audit).
     *
     * @return source identifier string
     */
    String sourceId();

    /**
     * URI scheme(s) this source handles.
     * Example: return {@code "file"} for {@code file://} URIs.
     *
     * @return the URI scheme string
     */
    String scheme();
}
