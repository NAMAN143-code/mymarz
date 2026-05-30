package com.mymarz.autoconfigure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MarzProperties.Platform}.
 *
 * <p>The platform sub-config has its own defaults and clamping rules
 * (heartbeat ≥ 5000ms). These tests pin them.</p>
 */
class MarzPropertiesPlatformTest {

    private MarzProperties.Platform platform;

    @BeforeEach
    void setUp() {
        platform = new MarzProperties.Platform();
    }

    // ═══════════════════════════════════════════════════════════════════
    // DEFAULTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("default endpoint points at the public platform")
    void defaultEndpoint() {
        assertThat(platform.getEndpoint()).isEqualTo("wss://api.mymarz.com/agent");
    }

    @Test
    @DisplayName("default API key is null (agent disabled by default)")
    void defaultApiKeyNull() {
        assertThat(platform.getApiKey()).isNull();
    }

    @Test
    @DisplayName("default environment is null (caller falls back to spring profiles)")
    void defaultEnvironmentNull() {
        assertThat(platform.getEnvironment()).isNull();
    }

    @Test
    @DisplayName("default heartbeat is 30s")
    void defaultHeartbeat() {
        assertThat(platform.getHeartbeatIntervalMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("default reconnect max delay is 60s")
    void defaultReconnectMaxDelay() {
        assertThat(platform.getReconnectMaxDelayMs()).isEqualTo(60_000L);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HEARTBEAT INTERVAL CLAMPING (minimum 5000ms)
    // ═══════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "heartbeat {0}ms → clamped to {1}ms")
    @CsvSource({
            "1, 5000",
            "100, 5000",
            "4999, 5000",
            "5000, 5000",
            "5001, 5001",
            "10000, 10000",
            "60000, 60000",
            "0, 5000",
            "-1, 5000",
    })
    void heartbeatClamping(long input, long expected) {
        platform.setHeartbeatIntervalMs(input);
        assertThat(platform.getHeartbeatIntervalMs()).isEqualTo(expected);
    }

    // ═══════════════════════════════════════════════════════════════════
    // RECONNECT MAX DELAY — NO CLAMPING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reconnectMaxDelay is not clamped — callers may set any value")
    void reconnectMaxDelayNotClamped() {
        platform.setReconnectMaxDelayMs(1L);
        assertThat(platform.getReconnectMaxDelayMs()).isEqualTo(1L);

        platform.setReconnectMaxDelayMs(0L);
        assertThat(platform.getReconnectMaxDelayMs()).isZero();

        platform.setReconnectMaxDelayMs(Long.MAX_VALUE);
        assertThat(platform.getReconnectMaxDelayMs()).isEqualTo(Long.MAX_VALUE);
    }

    // ═══════════════════════════════════════════════════════════════════
    // SETTER ROUND-TRIPS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("apiKey setter/getter round-trip")
    void apiKeyRoundTrip() {
        platform.setApiKey("marz_sk_abc123");
        assertThat(platform.getApiKey()).isEqualTo("marz_sk_abc123");

        platform.setApiKey(null);
        assertThat(platform.getApiKey()).isNull();
    }

    @Test
    @DisplayName("endpoint setter/getter round-trip")
    void endpointRoundTrip() {
        platform.setEndpoint("ws://localhost:8080/agent");
        assertThat(platform.getEndpoint()).isEqualTo("ws://localhost:8080/agent");
    }

    @Test
    @DisplayName("environment setter/getter round-trip")
    void environmentRoundTrip() {
        platform.setEnvironment("staging");
        assertThat(platform.getEnvironment()).isEqualTo("staging");

        platform.setEnvironment("");
        assertThat(platform.getEnvironment()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // PARENT POINTER
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MarzProperties exposes a non-null Platform sub-config by default")
    void parentHasPlatformByDefault() {
        MarzProperties props = new MarzProperties();
        assertThat(props.getPlatform()).isNotNull();
        assertThat(props.getPlatform()).isInstanceOf(MarzProperties.Platform.class);
    }

    @Test
    @DisplayName("Platform sub-config can be swapped via setter")
    void platformSwappable() {
        MarzProperties props = new MarzProperties();
        MarzProperties.Platform custom = new MarzProperties.Platform();
        custom.setApiKey("custom");

        props.setPlatform(custom);

        assertThat(props.getPlatform()).isSameAs(custom);
        assertThat(props.getPlatform().getApiKey()).isEqualTo("custom");
    }
}
