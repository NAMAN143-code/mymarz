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
 *   thread-pool-size: 2
 *   default-source: file:///etc/myapp/config.yml
 * </pre>
 *
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "hotswap")
public class HotSwapProperties {

    /** Master switch — set to false to disable all HotSwap processing. */
    private boolean enabled = true;

    /**
     * Default poll interval in milliseconds for fields that do not specify one.
     * Minimum: 500ms.
     */
    private long defaultPollIntervalMs = 5000L;

    /**
     * Thread pool size for the polling scheduler.
     * Defaults to {@code 0}, which lets the poller auto-size based on
     * {@code min(sourceCount, availableProcessors)}.
     */
    private int threadPoolSize = 0;

    /**
     * Default source URI used when a {@code @HotSwap} annotation does not
     * specify a source (i.e., source is the default {@code platform://hotswap}).
     * If null, platform-sourced fields remain unresolved until the platform
     * agent connects.
     */
    private String defaultSource;

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getDefaultPollIntervalMs() { return defaultPollIntervalMs; }
    public void setDefaultPollIntervalMs(long defaultPollIntervalMs) {
        this.defaultPollIntervalMs = defaultPollIntervalMs;
    }

    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }

    public String getDefaultSource() { return defaultSource; }
    public void setDefaultSource(String defaultSource) { this.defaultSource = defaultSource; }
}
