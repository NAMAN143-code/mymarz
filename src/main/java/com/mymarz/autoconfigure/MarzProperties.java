package com.mymarz.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the MARZ Spring Boot auto-configuration.
 *
 * <p>All properties are under the {@code marz} prefix:</p>
 * <pre>
 * marz:
 *   enabled: true
 *   safety-net-interval-ms: 60000
 *   default-source: file:///etc/myapp/config.yml
 *   platform:
 *     api-key: marz_sk_xxxxx
 *     endpoint: wss://api.mymarz.com/agent
 *     environment: production
 *     heartbeat-interval-ms: 30000
 *     reconnect-max-delay-ms: 60000
 * </pre>
 *
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "marz")
public class MarzProperties {

    /** Master switch — set to {@code false} to disable all MARZ processing. */
    private boolean enabled = true;

    /**
     * Safety-net CRC32 check interval in milliseconds. WatchService is the primary
     * event-driven mechanism; this periodic checksum runs as insurance for edge cases
     * (NFS mounts, macOS kqueue delays). Default: 60000ms (60s). Minimum: 500ms.
     */
    private long safetyNetIntervalMs = 60_000L;

    /**
     * Default source URI used when a {@code @Marz} annotation does not specify a source.
     * Set this to a file or HTTP URI to enable source-based initial value resolution
     * before the platform agent connects.
     */
    private String defaultSource;

    /** Platform connection configuration. */
    private Platform platform = new Platform();

    // ─── Getters / Setters ──────────────────────────────────────────

    /** @return whether MARZ processing is enabled */
    public boolean isEnabled() { return enabled; }

    /** @param enabled {@code false} to disable all MARZ processing */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** @return safety-net CRC32 check interval in milliseconds */
    public long getSafetyNetIntervalMs() { return safetyNetIntervalMs; }

    /**
     * Set the safety-net interval. Values below 500ms are clamped to 500ms.
     * @param safetyNetIntervalMs interval in milliseconds
     */
    public void setSafetyNetIntervalMs(long safetyNetIntervalMs) {
        this.safetyNetIntervalMs = Math.max(500L, safetyNetIntervalMs);
    }

    /** @return default source URI, or {@code null} if not configured */
    public String getDefaultSource() { return defaultSource; }

    /** @param defaultSource fallback source URI for fields using platform://marz */
    public void setDefaultSource(String defaultSource) { this.defaultSource = defaultSource; }

    /** @return platform connection configuration */
    public Platform getPlatform() { return platform; }

    /** @param platform platform connection configuration */
    public void setPlatform(Platform platform) { this.platform = platform; }

    // ═══════════════════════════════════════════════════════════════════
    // PLATFORM NESTED CONFIG (KAN-48)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * MARZ Platform connection properties.
     *
     * <p>The platform agent only activates when {@code marz.platform.api-key}
     * is set. Without it, MARZ operates in standalone mode with file-based
     * and HTTP config sources only.</p>
     */
    public static class Platform {

        /**
         * Platform API key. The agent only activates when this is set.
         * Obtain from the MARZ Platform dashboard → Settings → API Keys.
         */
        private String apiKey;

        /** Platform WebSocket endpoint. */
        private String endpoint = "wss://api.mymarz.com/agent";

        /**
         * Environment label (e.g., "production", "staging", "dev").
         * Falls back to {@code spring.profiles.active} if not set.
         */
        private String environment;

        /**
         * Heartbeat interval in milliseconds. The agent sends a heartbeat
         * with current field values at this interval. Minimum: 5000ms.
         */
        private long heartbeatIntervalMs = 30_000L;

        /**
         * Maximum reconnect backoff delay in milliseconds.
         * Reconnect uses exponential backoff: 5s → 10s → 20s → ... → cap at this value.
         */
        private long reconnectMaxDelayMs = 60_000L;

        // ── Getters / Setters ───────────────────────────────────────

        /** @return platform API key, or {@code null} if not configured */
        public String getApiKey() { return apiKey; }

        /** @param apiKey platform API key */
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        /** @return platform WebSocket endpoint */
        public String getEndpoint() { return endpoint; }

        /** @param endpoint platform WebSocket endpoint */
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        /** @return environment label, or {@code null} */
        public String getEnvironment() { return environment; }

        /** @param environment environment label */
        public void setEnvironment(String environment) { this.environment = environment; }

        /** @return heartbeat interval in milliseconds */
        public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }

        /**
         * Set the heartbeat interval. Values below 5000ms are clamped to 5000ms.
         * @param heartbeatIntervalMs heartbeat interval in milliseconds
         */
        public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = Math.max(5_000L, heartbeatIntervalMs);
        }

        /** @return maximum reconnect backoff delay in milliseconds */
        public long getReconnectMaxDelayMs() { return reconnectMaxDelayMs; }

        /** @param reconnectMaxDelayMs maximum reconnect backoff delay */
        public void setReconnectMaxDelayMs(long reconnectMaxDelayMs) {
            this.reconnectMaxDelayMs = reconnectMaxDelayMs;
        }
    }
}
