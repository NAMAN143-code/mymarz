package com.mymarz.autoconfigure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MarzProperties}.
 *
 * Covers defaults, poll interval clamping (ADR-001: minimum 500ms),
 * and getter/setter round-trips.
 */
class MarzPropertiesTest {

    private MarzProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MarzProperties();
    }

    // ═══════════════════════════════════════════════════════════════════
    // DEFAULTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("default enabled is true")
    void defaultEnabled() {
        assertThat(properties.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("default poll interval is 5000ms")
    void defaultPollInterval() {
        assertThat(properties.getDefaultPollIntervalMs()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("default source is null")
    void defaultSourceIsNull() {
        assertThat(properties.getDefaultSource()).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // POLL INTERVAL CLAMPING (ADR-001 §4: minimum 500ms)
    // ═══════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @CsvSource({
        "1, 500",       // 1ms → clamped to 500ms
        "100, 500",     // 100ms → clamped to 500ms
        "499, 500",     // 499ms → clamped to 500ms
        "500, 500",     // 500ms → exactly 500ms (boundary)
        "501, 501",     // 501ms → accepted as-is
        "5000, 5000",   // 5000ms → accepted as-is
        "0, 500",       // 0ms → clamped to 500ms
        "-1, 500",      // negative → clamped to 500ms
    })
    @DisplayName("poll interval below 500ms is clamped to 500ms")
    void pollIntervalClamping(long input, long expected) {
        properties.setDefaultPollIntervalMs(input);
        assertThat(properties.getDefaultPollIntervalMs()).isEqualTo(expected);
    }

    @Test
    @DisplayName("large poll interval values are accepted")
    void largePollInterval() {
        properties.setDefaultPollIntervalMs(60_000L);
        assertThat(properties.getDefaultPollIntervalMs()).isEqualTo(60_000L);
    }

    // ═══════════════════════════════════════════════════════════════════
    // SETTER / GETTER ROUND-TRIPS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("enabled setter/getter round-trip")
    void enabledRoundTrip() {
        properties.setEnabled(false);
        assertThat(properties.isEnabled()).isFalse();

        properties.setEnabled(true);
        assertThat(properties.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("defaultSource setter/getter round-trip")
    void defaultSourceRoundTrip() {
        properties.setDefaultSource("file:///etc/myapp/config.yml");
        assertThat(properties.getDefaultSource()).isEqualTo("file:///etc/myapp/config.yml");
    }

    @Test
    @DisplayName("defaultSource can be set to null")
    void defaultSourceNull() {
        properties.setDefaultSource("something");
        properties.setDefaultSource(null);
        assertThat(properties.getDefaultSource()).isNull();
    }

    @Test
    @DisplayName("defaultSource can be empty string")
    void defaultSourceEmpty() {
        properties.setDefaultSource("");
        assertThat(properties.getDefaultSource()).isEmpty();
    }
}
