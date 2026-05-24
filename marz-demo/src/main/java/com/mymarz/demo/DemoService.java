package com.mymarz.demo;

import com.mymarz.annotation.MARZ;
import com.mymarz.annotation.MarzType;
import org.springframework.stereotype.Service;

/**
 * Service demonstrating @Marz annotation on different field types.
 *
 * Each field reads from config/marz-demo.yml. Edit that file while
 * the app is running — values update in-memory without restart.
 */
@Service
public class DemoService {

    // ─── Feature Flags (boolean) ────────────────────────────────────

    @Marz(
            key = "feature.new-checkout.enabled",
            source = "file://config/marz-demo.yml",
            description = "Enable the new checkout flow"
    )
    private volatile boolean newCheckoutEnabled = false;

    @Marz(
            key = "feature.dark-mode.enabled",
            source = "file://config/marz-demo.yml",
            description = "Enable dark mode UI"
    )
    private volatile boolean darkModeEnabled = false;

    // ─── Rate Limiting (int) ────────────────────────────────────────

    @Marz(
            key = "rate-limit.max-requests-per-minute",
            source = "file://config/marz-demo.yml",
            description = "Max API requests per minute per client"
    )
    private volatile int maxRequestsPerMinute = 100;

    // ─── Application Config (String, double) ────────────────────────

    @Marz(
            key = "app.welcome-message",
            source = "file://config/marz-demo.yml",
            description = "Welcome message shown on the homepage"
    )
    private volatile String welcomeMessage = "Welcome to MARZ Demo!";

    @Marz(
            key = "app.discount-rate",
            source = "file://config/marz-demo.yml",
            type = MarzType.DOUBLE,
            description = "Current discount rate (0.0 - 1.0)"
    )
    private volatile double discountRate = 0.0;

    // ─── Sensitive Config ───────────────────────────────────────────

    @Marz(
            key = "secrets.api-key",
            source = "file://config/marz-demo.yml",
            sensitive = true,
            description = "Third-party API key (masked in logs)"
    )
    private volatile String apiKey = "default-key";

    // ─── Getters ────────────────────────────────────────────────────

    public boolean isNewCheckoutEnabled() { return newCheckoutEnabled; }
    public boolean isDarkModeEnabled() { return darkModeEnabled; }
    public int getMaxRequestsPerMinute() { return maxRequestsPerMinute; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public double getDiscountRate() { return discountRate; }
    public String getApiKey() { return apiKey; }
}
