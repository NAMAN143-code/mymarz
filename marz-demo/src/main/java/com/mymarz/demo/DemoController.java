package com.mymarz.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoints that return current @Marz values.
 *
 * Try these while editing config/marz-demo.yml:
 *   curl localhost:8080/config
 *   curl localhost:8080/checkout
 */
@RestController
public class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    /**
     * Shows all @Marz values at once.
     * Edit config/marz-demo.yml and refresh to see changes.
     */
    @GetMapping("/config")
    public Map<String, Object> getAllConfig() {
        return Map.of(
                "feature.new-checkout.enabled", demoService.isNewCheckoutEnabled(),
                "feature.dark-mode.enabled", demoService.isDarkModeEnabled(),
                "rate-limit.max-requests-per-minute", demoService.getMaxRequestsPerMinute(),
                "app.welcome-message", demoService.getWelcomeMessage(),
                "app.discount-rate", demoService.getDiscountRate(),
                "secrets.api-key", "***" // Never expose sensitive values
        );
    }

    /**
     * Simulates a checkout flow that uses the feature flag.
     */
    @GetMapping("/checkout")
    public Map<String, Object> checkout() {
        if (demoService.isNewCheckoutEnabled()) {
            return Map.of(
                    "flow", "NEW checkout (v2)",
                    "discount", demoService.getDiscountRate(),
                    "message", "Using the new checkout experience!"
            );
        }
        return Map.of(
                "flow", "LEGACY checkout (v1)",
                "discount", 0.0,
                "message", "Classic checkout — toggle feature.new-checkout.enabled to try v2"
        );
    }

    /**
     * Shows the welcome message — change it in config without restart.
     */
    @GetMapping("/")
    public Map<String, String> home() {
        return Map.of(
                "message", demoService.getWelcomeMessage(),
                "darkMode", String.valueOf(demoService.isDarkModeEnabled())
        );
    }
}
