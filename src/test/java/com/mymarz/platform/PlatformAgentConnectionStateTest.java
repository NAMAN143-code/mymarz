package com.mymarz.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link PlatformAgent.ConnectionState} enum.
 *
 * <p>Pins the names and ordering so renaming a state would surface here
 * (it would also break {@link PlatformHealthIndicator}'s switch and external
 * monitoring dashboards that pattern-match on the state string).</p>
 */
class PlatformAgentConnectionStateTest {

    @Test
    @DisplayName("enum contains exactly four states")
    void enumValues() {
        assertThat(PlatformAgent.ConnectionState.values())
                .containsExactlyInAnyOrder(
                        PlatformAgent.ConnectionState.DISCONNECTED,
                        PlatformAgent.ConnectionState.CONNECTING,
                        PlatformAgent.ConnectionState.CONNECTED,
                        PlatformAgent.ConnectionState.RECONNECTING);
    }

    @ParameterizedTest
    @EnumSource(PlatformAgent.ConnectionState.class)
    @DisplayName("valueOf round-trips for every state")
    void valueOfRoundTrip(PlatformAgent.ConnectionState s) {
        assertThat(PlatformAgent.ConnectionState.valueOf(s.name())).isSameAs(s);
    }

    @Test
    @DisplayName("valueOf rejects unknown names")
    void valueOfRejectsUnknown() {
        assertThatThrownBy(() -> PlatformAgent.ConnectionState.valueOf("HALF_OPEN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("names are stable strings (DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING)")
    void namesAreStable() {
        // Pin the wire format used by PlatformHealthIndicator details
        assertThat(PlatformAgent.ConnectionState.DISCONNECTED.name()).isEqualTo("DISCONNECTED");
        assertThat(PlatformAgent.ConnectionState.CONNECTING.name()).isEqualTo("CONNECTING");
        assertThat(PlatformAgent.ConnectionState.CONNECTED.name()).isEqualTo("CONNECTED");
        assertThat(PlatformAgent.ConnectionState.RECONNECTING.name()).isEqualTo("RECONNECTING");
    }

    @Test
    @DisplayName("DISCONNECTED is the first ordinal — the initial state of the agent")
    void disconnectedFirst() {
        assertThat(PlatformAgent.ConnectionState.values()[0])
                .isEqualTo(PlatformAgent.ConnectionState.DISCONNECTED);
    }
}
