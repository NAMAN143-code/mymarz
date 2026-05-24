package com.mymarz.platform;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Exposes MARZ Platform connection state via Spring Boot Actuator.
 *
 * <p>Appears as {@code marzPlatform} in {@code /actuator/health}:</p>
 * <pre>
 * {
 *   "marzPlatform": {
 *     "status": "UP",
 *     "details": { "state": "CONNECTED", "endpoint": "wss://..." }
 *   }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public class PlatformHealthIndicator implements HealthIndicator {

    private final PlatformAgent agent;
    private final String endpoint;

    public PlatformHealthIndicator(PlatformAgent agent, String endpoint) {
        this.agent = agent;
        this.endpoint = endpoint;
    }

    @Override
    public Health health() {
        PlatformAgent.ConnectionState state = agent.getState();

        Health.Builder builder = switch (state) {
            case CONNECTED -> Health.up();
            case CONNECTING, RECONNECTING -> Health.status("CONNECTING");
            case DISCONNECTED -> Health.down();
        };

        return builder
                .withDetail("state", state.name())
                .withDetail("endpoint", endpoint)
                .build();
    }
}
