package com.hotswap.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the HotSwap Spring Boot auto-configuration.
 *
 * <p>All properties are under the {@code hotswap} prefix in
 * {@code application.yml} / {@code application.properties}.</p>
 *
 * <p>Example {@code application.yml}:</p>
 * <pre>
 * hotswap:
 *   enabled: true
 *   default-poll-interval-ms: 5000
 *   default-source: file:///etc/myapp/config.yml
 * </pre>
 *
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "hotswap")
public class HotSwapProperties {

    /** Master switch — set to {@code false} to disable all HotSwap processing. */
    private boolean enabled = true;

    /**
     * Default poll interval in milliseconds for HTTP config sources
     * that do not specify their own interval. Minimum enforced: 500ms.
     */
    private long defaultPollIntervalMs = 5000L;

    /**
     * Default source URI used when a {@code @HotSwap} annotation does not
     * specify a source (i.e., source is the default {@code platform://hotswap}).
     *
     * <p>Set this to a file or HTTP URI to enable source-based initial value
     * resolution before the platform agent connects. Example:
     * {@code file:///etc/myapp/config.yml}</p>
     */
    private String defaultSource;

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    /** @return whether HotSwap processing is enabled */
    public boolean isEnabled() { return enabled; }

    /** @param enabled {@code false} to disable all HotSwap processing */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** @return default poll interval in milliseconds (minimum 500ms) */
    public long getDefaultPollIntervalMs() { return defaultPollIntervalMs; }

    /**
     * Set the default poll interval. Values below 500ms are clamped to 500ms.
     * @param defaultPollIntervalMs poll interval in milliseconds
     */
    public void setDefaultPollIntervalMs(long defaultPollIntervalMs) {
        this.defaultPollIntervalMs = Math.max(500L, defaultPollIntervalMs);
    }

    /** @return default source URI, or {@code null} if not configured */
    public String getDefaultSource() { return defaultSource; }

    /** @param defaultSource fallback source URI for fields using platform://hotswap */
    public void setDefaultSource(String defaultSource) { this.defaultSource = defaultSource; }
}
