package com.mymarz.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the default methods on the {@link ConfigSource} SPI.
 *
 * <p>{@code start()}, {@code stop()}, and {@code isRunning()} have default
 * implementations so SPI authors only need to implement the read methods.
 * These tests pin that contract.</p>
 */
class ConfigSourceDefaultsTest {

    /** Minimal implementation that uses every default. */
    static class MinimalSource implements ConfigSource {
        @Override public String resolve(String key) { return "value-for-" + key; }
        @Override public boolean isAvailable() { return true; }
        @Override public String sourceId() { return "minimal"; }
        @Override public String scheme() { return "minimal"; }
    }

    @Test
    @DisplayName("default start() is a no-op (does not throw)")
    void defaultStartNoop() {
        ConfigSource source = new MinimalSource();
        source.start();
        source.start(); // repeatable
    }

    @Test
    @DisplayName("default stop() is a no-op (does not throw)")
    void defaultStopNoop() {
        ConfigSource source = new MinimalSource();
        source.stop();
        source.stop(); // repeatable
    }

    @Test
    @DisplayName("default isRunning() returns false")
    void defaultIsRunningFalse() {
        assertThat(new MinimalSource().isRunning()).isFalse();
    }

    @Test
    @DisplayName("default start() does not change isRunning() — implementer's responsibility")
    void startDoesNotFlipIsRunning() {
        ConfigSource source = new MinimalSource();
        source.start();
        // The default contract makes no claims about isRunning() after start()
        assertThat(source.isRunning()).isFalse();
    }

    @Test
    @DisplayName("required methods (resolve, isAvailable, sourceId, scheme) are honoured")
    void abstractMethodsWorking() {
        ConfigSource source = new MinimalSource();
        assertThat(source.resolve("key")).isEqualTo("value-for-key");
        assertThat(source.isAvailable()).isTrue();
        assertThat(source.sourceId()).isEqualTo("minimal");
        assertThat(source.scheme()).isEqualTo("minimal");
    }

    @Test
    @DisplayName("an override-everything source can replace defaults independently")
    void overrideStartStopIsRunning() {
        var source = new ConfigSource() {
            boolean running = false;
            @Override public String resolve(String key) { return null; }
            @Override public boolean isAvailable() { return true; }
            @Override public String sourceId() { return "override"; }
            @Override public String scheme() { return "test"; }
            @Override public void start() { running = true; }
            @Override public void stop() { running = false; }
            @Override public boolean isRunning() { return running; }
        };

        assertThat(source.isRunning()).isFalse();
        source.start();
        assertThat(source.isRunning()).isTrue();
        source.stop();
        assertThat(source.isRunning()).isFalse();
    }
}
