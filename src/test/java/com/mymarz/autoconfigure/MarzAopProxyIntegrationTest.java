package com.mymarz.autoconfigure;

import com.mymarz.annotation.Marz;
import com.mymarz.core.MarzRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAN-96 end-to-end: a real Spring-container-created CGLIB proxy (forced here via
 * {@code @Async}, identical proxying mechanics to {@code @Transactional}/
 * {@code @Cacheable}) must still receive its initial {@code @Marz} value and have a
 * runtime swap visible through a method invoked on the proxy.
 */
@SpringBootTest(classes = MarzAopProxyIntegrationTest.Config.class)
class MarzAopProxyIntegrationTest {

    @Configuration
    @EnableAsync
    @ImportAutoConfiguration(MarzAutoConfiguration.class)
    static class Config {
        @Bean
        ProxiedFeatureService proxiedFeatureService() {
            return new ProxiedFeatureService();
        }
    }

    static class ProxiedFeatureService {
        @Marz(key = "feature.async.enabled", defaultValue = "false")
        volatile boolean enabled;

        // Presence of an @Async method forces Spring to create a (CGLIB) proxy for
        // this bean — the exact situation that previously broke @Marz binding.
        @Async
        public void touch() { /* never invoked by the test; only forces proxying */ }

        public boolean isEnabled() {
            return enabled;
        }
    }

    @Autowired
    ProxiedFeatureService service; // injected as the proxy

    @Autowired
    MarzRegistry registry;

    @Test
    @DisplayName("proxied bean: initial value applies and a runtime swap is visible through the proxy")
    void proxiedBean_initialApplies_andSwapVisible() {
        // Sanity: the injected bean really is a proxy.
        assertThat(AopUtils.isAopProxy(service)).isTrue();

        // Registered once (to the target), initial value visible through the proxy.
        assertThat(registry.getBindings("feature.async.enabled")).hasSize(1);
        assertThat(service.isEnabled()).isFalse();

        // Runtime swap is visible through a method invoked on the proxy.
        registry.onSourceChange("platform://marz", Map.of("feature.async.enabled", "true"));
        assertThat(service.isEnabled()).isTrue();
    }
}
