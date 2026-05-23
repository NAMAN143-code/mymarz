package com.hotswap.demo;

import com.hotswap.annotation.HotSwap;
import com.hotswap.annotation.HotSwapType;
import org.springframework.stereotype.Service;

/**
 * Service demonstrating @HotSwap annotation on different field types.
 *
 * Each field reads from config/hotswap-demo.yml. Edit that file while
 * the app is running — values update in-memory without restart.
 */
@Service
public class DemoService {

    // ─── Feature Flags (boolean) ────────────────────────────────────

    @HotSwap(
            key = "feature.new-checkout.enabled",
            source = "file://config/hotswap-demo.yml",
            description = "Enable the new checkout flow"
    )
    private volatile boolean newCheckoutEnabled = false;

    @HotSwap(
            key = "feature.dark-mode.enabled",
            source = "file://config/hotswap-demo.yml",
            description = "Enable dark mode UI"
    )
    private volatile boolean darkModeEnabled = false;

    // ─── Rate Limiting (int) ────────────────────────────────────────

    @HotSwap(
            key = "rate-limit.max-requests-per-minute",
            source = "file://config/hotswap-demo.yml",
            description = "Max API requests per minute per client"
    )
    private volatile int maxRequestsPerMinute = 100;

    // ─── Application Config (String, double) ────────────────────────

    @HotSwap(
            key = "app.welcome-message",
            source = "file://config/hotswap-demo.yml",
            description = "Welcome message shown on the homepage"
    )
    private volatile String welcomeMessage = "Welcome to HotSwap Demo!";

    @HotSwap(
            key = "app.discount-rate",
            source = "file://config/hotswap-demo.yml",
            type = HotSwapType.DOUBLE,
            description = "Current discount rate (0.0 - 1.0)"
    )
    private volatile double discountRate = 0.0;

    // ─── Sensitive Config ───────────────────────────────────────────

    @HotSwap(
            key = "secrets.api-key",
            source = "file://config/hotswap-demo.yml",
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
