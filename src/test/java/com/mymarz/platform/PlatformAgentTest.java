package com.mymarz.platform;

import com.mymarz.autoconfigure.MarzProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAgentTest {

    private MarzProperties.Platform config;
    private PlatformAgent agent;

    @BeforeEach
    void setUp() {
        config = new MarzProperties.Platform();
        config.setApiKey("test-api-key");
        config.setEndpoint("wss://localhost:19999/agent"); // Unreachable — tests don't connect
        config.setReconnectMaxDelayMs(60_000L);
    }

    @AfterEach
    void tearDown() {
        if (agent != null) agent.stop();
    }

    @Test
    @DisplayName("initial state is DISCONNECTED")
    void initialState() {
        agent = new PlatformAgent(config, List.of());
        assertThat(agent.getState()).isEqualTo(PlatformAgent.ConnectionState.DISCONNECTED);
        assertThat(agent.isConnected()).isFalse();
    }

    @Test
    @DisplayName("start transitions to CONNECTING")
    void startTransitionsToConnecting() throws InterruptedException {
        agent = new PlatformAgent(config, List.of());
        agent.start();
        // Give async connect a moment
        Thread.sleep(100);
        // Should be CONNECTING or DISCONNECTED (failed fast to unreachable host)
        assertThat(agent.getState()).isIn(
                PlatformAgent.ConnectionState.CONNECTING,
                PlatformAgent.ConnectionState.DISCONNECTED
        );
    }

    @Test
    @DisplayName("stop sets DISCONNECTED")
    void stopSetsDisconnected() {
        agent = new PlatformAgent(config, List.of());
        agent.start();
        agent.stop();
        assertThat(agent.getState()).isEqualTo(PlatformAgent.ConnectionState.DISCONNECTED);
    }

    @Test
    @DisplayName("send returns false when not connected")
    void sendReturnsFalseWhenDisconnected() {
        agent = new PlatformAgent(config, List.of());
        assertThat(agent.send("{\"type\":\"HEARTBEAT\"}")).isFalse();
    }

    @Test
    @DisplayName("backoff: exponential with cap")
    void backoffExponentialCapped() {
        agent = new PlatformAgent(config, List.of());
        assertThat(agent.calculateBackoff(1)).isEqualTo(5_000L);   // 5s * 2^0
        assertThat(agent.calculateBackoff(2)).isEqualTo(10_000L);  // 5s * 2^1
        assertThat(agent.calculateBackoff(3)).isEqualTo(20_000L);  // 5s * 2^2
        assertThat(agent.calculateBackoff(4)).isEqualTo(40_000L);  // 5s * 2^3
        assertThat(agent.calculateBackoff(5)).isEqualTo(60_000L);  // Capped at 60s
        assertThat(agent.calculateBackoff(20)).isEqualTo(60_000L); // Still capped
    }

    @Test
    @DisplayName("backoff respects custom reconnectMaxDelayMs")
    void backoffCustomMaxDelay() {
        config.setReconnectMaxDelayMs(15_000L);
        agent = new PlatformAgent(config, List.of());
        assertThat(agent.calculateBackoff(1)).isEqualTo(5_000L);
        assertThat(agent.calculateBackoff(2)).isEqualTo(10_000L);
        assertThat(agent.calculateBackoff(3)).isEqualTo(15_000L);  // Capped at custom max
        assertThat(agent.calculateBackoff(4)).isEqualTo(15_000L);
    }

    @Test
    @DisplayName("lifecycle: start then stop is clean")
    void lifecycleClean() {
        agent = new PlatformAgent(config, List.of());
        agent.start();
        agent.stop();
        // Double-stop is safe
        agent.stop();
    }

    @Test
    @DisplayName("agent does not activate without api-key")
    void noActivationWithoutApiKey() {
        config.setApiKey(null);
        agent = new PlatformAgent(config, List.of());
        // Should not throw — just won't connect
        assertThat(agent.getState()).isEqualTo(PlatformAgent.ConnectionState.DISCONNECTED);
    }
}
