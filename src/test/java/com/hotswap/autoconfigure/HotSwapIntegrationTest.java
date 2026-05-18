package com.hotswap.autoconfigure;

import com.hotswap.annotation.HotSwap;
import com.hotswap.core.HotSwapEvent;
import com.hotswap.core.HotSwapRegistry;
import com.hotswap.core.ConfigSourcePoller;
import com.hotswap.core.ConfigSourceFactory;
import com.hotswap.core.HotSwapBeanPostProcessor;
import com.hotswap.source.ConfigFormatParser;
import com.hotswap.type.TypeCoercer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for {@link HotSwapAutoConfiguration}.
 *
 * <p>Uses {@link SpringBootConfiguration} + {@link ImportAutoConfiguration}
 * rather than {@code @SpringBootApplication}, so we load ONLY our auto-config
 * and avoid any interference from Spring Boot's built-in auto-configurations
 * (DataSource, Web, etc.) that might fail without their required dependencies.</p>
 */
@SpringBootTest(classes = HotSwapIntegrationTest.TestApp.class)
@TestPropertySource(properties = {
        "hotswap.enabled=true",
        "hotswap.default-poll-interval-ms=200",
        "hotswap.thread-pool-size=1"
})
class HotSwapIntegrationTest {

    /**
     * Minimal bootstrap config.
     * - @SpringBootConfiguration makes this discoverable by @SpringBootTest
     * - @ImportAutoConfiguration loads ONLY HotSwapAutoConfiguration (not all of Spring Boot)
     */
    @SpringBootConfiguration
    @ImportAutoConfiguration(HotSwapAutoConfiguration.class)
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

    /** Bean with @HotSwap-annotated fields (default source: platform://hotswap) */
    static class FeatureBean {

        @HotSwap(key = "feature.checkout.v2", pollInterval = 200)
        private boolean checkoutV2 = false;

        @HotSwap(key = "rate.limit", pollInterval = 200)
        private int rateLimit = 0;

        boolean isCheckoutV2() { return checkoutV2; }
        int getRateLimit()      { return rateLimit;  }
    }

    /** Captures HotSwapEvents for assertion */
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

    @Autowired HotSwapRegistry          registry;
    @Autowired HotSwapProperties        properties;
    @Autowired ConfigSourcePoller       poller;
    @Autowired ConfigSourceFactory      sourceFactory;
    @Autowired TypeCoercer              typeCoercer;
    @Autowired ConfigFormatParser       formatParser;
    @Autowired HotSwapBeanPostProcessor beanPostProcessor;
    @Autowired EventCaptor              eventCaptor;

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all HotSwap infrastructure beans are present in the context")
    void allInfrastructureBeansPresent() {
        assertThat(registry).isNotNull();
        assertThat(poller).isNotNull();
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
        assertThat(registry.getFieldCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("registry records the correct source URI for registered fields")
    void registryRecordsSourceUri() {
        assertThat(registry.getAllSourceUris())
                .anyMatch(uri -> uri.equals("platform://hotswap"));
    }

    @Test
    @DisplayName("ConfigSourceFactory has file:// scheme pre-registered")
    void fileSchemePreRegistered() {
        assertThat(sourceFactory.create("file:///tmp/nonexistent-test.yml")).isNotNull();
    }

    @Test
    @DisplayName("EventCaptor bean is wired and ready")
    void eventCaptorIsWired() {
        assertThat(eventCaptor).isNotNull();
        assertThat(eventCaptor.events).isEmpty();
    }
}
