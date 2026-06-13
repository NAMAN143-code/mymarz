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
 * KAN-96 end-to-end: real Spring-container-created proxies must still receive their
 * initial {@code @Marz} value and have a runtime swap visible through a method invoked
 * on the proxy. Both proxy flavours are exercised in a live container here:
 *
 * <ul>
 *   <li><strong>CGLIB</strong> — a concrete (no-interface) bean with an {@code @Async}
 *       method, the same auto-proxy path {@code @Transactional}/{@code @Cacheable} use.</li>
 *   <li><strong>JDK dynamic proxy</strong> — an interface-implementing bean with an
 *       {@code @Async} method (default {@code proxyTargetClass=false}), injected by its
 *       interface.</li>
 * </ul>
 *
 * <p>The dedicated {@code @Transactional} CGLIB case lives in
 * {@link MarzTransactionalProxyIntegrationTest}.</p>
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

        // Interface return type + @Async + default proxyTargetClass=false → JDK dynamic proxy.
        @Bean
        JdkFeatureService jdkFeatureService() {
            return new JdkFeatureServiceImpl();
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

    interface JdkFeatureService {
        boolean isJdkEnabled();
    }

    static class JdkFeatureServiceImpl implements JdkFeatureService {
        @Marz(key = "feature.jdk.enabled", defaultValue = "false")
        volatile boolean enabled;

        @Async
        public void touch() { /* forces an interface-based JDK proxy */ }

        @Override
        public boolean isJdkEnabled() {
            return enabled;
        }
    }

    @Autowired
    ProxiedFeatureService service; // injected as the CGLIB proxy

    @Autowired
    JdkFeatureService jdkService; // injected as the JDK dynamic proxy (interface type)

    @Autowired
    MarzRegistry registry;

    @Test
    @DisplayName("CGLIB-proxied bean: initial value applies and a runtime swap is visible through the proxy")
    void cglibProxiedBean_initialApplies_andSwapVisible() {
        assertThat(AopUtils.isCglibProxy(service)).isTrue();

        assertThat(registry.getBindings("feature.async.enabled")).hasSize(1);
        assertThat(service.isEnabled()).isFalse();

        registry.onSourceChange("platform://marz", Map.of("feature.async.enabled", "true"));
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("JDK interface-proxied bean: initial value applies and a runtime swap is visible through the proxy")
    void jdkProxiedBean_initialApplies_andSwapVisible() {
        // Sanity: the injected bean is a JDK dynamic proxy, not the concrete class.
        assertThat(AopUtils.isJdkDynamicProxy(jdkService)).isTrue();

        // Bound once (to the target), initial value visible through the interface method.
        assertThat(registry.getBindings("feature.jdk.enabled")).hasSize(1);
        assertThat(jdkService.isJdkEnabled()).isFalse();

        // Runtime swap is visible through a method invoked on the proxy.
        registry.onSourceChange("platform://marz", Map.of("feature.jdk.enabled", "true"));
        assertThat(jdkService.isJdkEnabled()).isTrue();
    }
}
