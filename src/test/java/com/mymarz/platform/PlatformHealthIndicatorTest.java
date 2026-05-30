package com.mymarz.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlatformHealthIndicator}.
 *
 * <p>Maps every {@link PlatformAgent.ConnectionState} to a Spring Boot
 * Actuator {@link Status} and asserts that the {@code state}/{@code endpoint}
 * details are always present on the response.</p>
 */
class PlatformHealthIndicatorTest {

    private static final String ENDPOINT = "wss://api.mymarz.com/agent";

    private PlatformAgent agent;
    private PlatformHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        agent = mock(PlatformAgent.class);
        indicator = new PlatformHealthIndicator(agent, ENDPOINT);
    }

    @Test
    @DisplayName("CONNECTED → UP")
    void connectedIsUp() {
        when(agent.getState()).thenReturn(PlatformAgent.ConnectionState.CONNECTED);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "CONNECTED");
        assertThat(health.getDetails()).containsEntry("endpoint", ENDPOINT);
    }

    @Test
    @DisplayName("CONNECTING → custom CONNECTING status")
    void connectingIsCustomStatus() {
        when(agent.getState()).thenReturn(PlatformAgent.ConnectionState.CONNECTING);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("CONNECTING");
        assertThat(health.getDetails()).containsEntry("state", "CONNECTING");
    }

    @Test
    @DisplayName("RECONNECTING → custom CONNECTING status (same as CONNECTING)")
    void reconnectingIsCustomStatus() {
        when(agent.getState()).thenReturn(PlatformAgent.ConnectionState.RECONNECTING);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("CONNECTING");
        assertThat(health.getDetails()).containsEntry("state", "RECONNECTING");
    }

    @Test
    @DisplayName("DISCONNECTED → DOWN")
    void disconnectedIsDown() {
        when(agent.getState()).thenReturn(PlatformAgent.ConnectionState.DISCONNECTED);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("state", "DISCONNECTED");
    }

    @ParameterizedTest
    @EnumSource(PlatformAgent.ConnectionState.class)
    @DisplayName("every connection state produces a non-null Health with state + endpoint details")
    void everyStateProducesDetails(PlatformAgent.ConnectionState state) {
        when(agent.getState()).thenReturn(state);

        Health health = indicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isNotNull();
        assertThat(health.getDetails())
                .containsEntry("state", state.name())
                .containsEntry("endpoint", ENDPOINT);
    }

    @Test
    @DisplayName("endpoint is rendered as a String detail")
    void endpointDetailContainsConfiguredString() {
        when(agent.getState()).thenReturn(PlatformAgent.ConnectionState.CONNECTED);

        Health health = indicator.health();

        assertThat(health.getDetails().get("endpoint"))
                .isInstanceOf(String.class)
                .isEqualTo(ENDPOINT);
    }

    @Test
    @DisplayName("a non-default endpoint string is reflected in the details")
    void customEndpointFlowsThrough() {
        PlatformHealthIndicator custom = new PlatformHealthIndicator(agent, "ws://staging.local/agent");
        when(agent.getState()).thenReturn(PlatformAgent.ConnectionState.CONNECTED);

        Health health = custom.health();

        assertThat(health.getDetails()).containsEntry("endpoint", "ws://staging.local/agent");
    }

    @Test
    @DisplayName("health() re-reads agent state on each call")
    void healthReadsCurrentState() {
        when(agent.getState())
                .thenReturn(PlatformAgent.ConnectionState.CONNECTING)
                .thenReturn(PlatformAgent.ConnectionState.CONNECTED);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("CONNECTING");
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
