package com.hotswap.autoconfigure;

import com.hotswap.annotation.HotSwap;
import com.hotswap.core.HotSwapEvent;
import com.hotswap.core.HotSwapRegistry;
import com.hotswap.core.ConfigSourcePoller;
import com.hotswap.core.ConfigSourceFactory;
import com.hotswap.type.TypeCoercer;
import com.hotswap.source.ConfigFormatParser;
import com.hotswap.core.HotSwapBeanPostProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for {@link HotSwapAutoConfiguration}.
 *
 * <p>Uses a minimal {@code @SpringBootApplication} bootstrap class so that
 * Spring Boot's {@code AutoConfiguration.imports} scanning is activated,
 * which in turn loads {@link HotSwapAutoConfiguration} automatically.</p>
 *
 * <p>This test focuses on infrastructure wiring — that all beans are present,
 * properties bind correctly, and the context starts cleanly. Per-field hot-swap
 * behaviour is covered by {@code ConfigSourcePollerTest} and
 * {@code FileConfigSourceTest}.</p>
 */
@SpringBootTest(classes = HotSwapIntegrationTest.TestApp.class)
@TestPropertySource(properties = {
        "hotswap.enabled=true",
        "hotswap.default-poll-interval-ms=200",
        "hotswap.thread-pool-size=1"
})
class HotSwapIntegrationTest {

    // -------------------------------------------------------------------------
    // Minimal Spring Boot application — activates AutoConfiguration.imports
    // -------------------------------------------------------------------------

    @SpringBootApplication
    @Configuration
    static class TestApp {

        @Bean
        public FeatureBean featureBean() {
            return new FeatureBean();
        }

        @Bean
        public EventCaptor eventCaptor() {
            return new EventCaptor();
        }
    }

    // -------------------------------------------------------------------------
    // Test beans
    // -------------------------------------------------------------------------

    /**
     * A bean with two {@code @HotSwap}-annotated fields.
     * Uses the default source ({@code platform://hotswap}) — the fields will be
     * registered in the registry; no source resolution error occurs, they simply
     * retain their default values until a real platform agent connects.
     */
    static class FeatureBean {

        @HotSwap(key = "feature.checkout.v2", pollInterval = 200)
        private boolean checkoutV2 = false;

        @HotSwap(key = "rate.limit", pollInterval = 200)
        private int rateLimit = 0;

        boolean isCheckoutV2() { return checkoutV2; }
        int getRateLimit()      { return rateLimit;  }
    }

    /** Captures {@link HotSwapEvent}s fired during the test. */
    static class EventCaptor {
        final CopyOnWriteArrayList<HotSwapEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void onHotSwapEvent(HotSwapEvent event) {
            events.add(event);
        }
    }

    // -------------------------------------------------------------------------
    // Injected beans
    // -------------------------------------------------------------------------

    @Autowired HotSwapRegistry         registry;
    @Autowired HotSwapProperties       properties;
    @Autowired ConfigSourcePoller      poller;
    @Autowired HotSwapAutoConfiguration autoConfig;
    @Autowired ConfigSourceFactory     sourceFactory;
    @Autowired TypeCoercer             typeCoercer;
    @Autowired ConfigFormatParser      formatParser;
    @Autowired HotSwapBeanPostProcessor beanPostProcessor;
    @Autowired EventCaptor             eventCaptor;

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all HotSwap infrastructure beans are present in the context")
    void allInfrastructureBeansPresent() {
        assertThat(registry).isNotNull();
        assertThat(poller).isNotNull();
        assertThat(autoConfig).isNotNull();
        assertThat(sourceFactory).isNotNull();
        assertThat(typeCoercer).isNotNull();
        assertThat(formatParser).isNotNull();
        assertThat(beanPostProcessor).isNotNull();
    }

    @Test
    @DisplayName("hotswap.* properties bind correctly from @TestPropertySource")
    void propertiesBindCorrectly() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDefaultPollIntervalMs()).isEqualTo(200L);
        assertThat(properties.getThreadPoolSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("FeatureBean @HotSwap fields are registered in the registry")
    void hotSwapFieldsRegistered() {
        // Both fields on FeatureBean should be registered at startup
        assertThat(registry.getFieldCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("registry records the correct source URI for registered fields")
    void registryRecordsSourceUri() {
        // Default source is platform://hotswap (annotation default)
        assertThat(registry.getAllSourceUris())
                .anyMatch(uri -> uri.equals("platform://hotswap"));
    }

    @Test
    @DisplayName("ConfigSourceFactory has file:// scheme pre-registered")
    void fileSchemePreRegistered() {
        // Creating a file:// source should not return null
        assertThat(sourceFactory.create("file:///tmp/nonexistent-test.yml")).isNotNull();
    }

    @Test
    @DisplayName("EventCaptor bean is wired and ready")
    void eventCaptorIsWired() {
        assertThat(eventCaptor).isNotNull();
        assertThat(eventCaptor.events).isEmpty();
    }
}
