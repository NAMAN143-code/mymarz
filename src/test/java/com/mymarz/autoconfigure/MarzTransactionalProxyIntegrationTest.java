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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAN-96, verbatim criterion 3: a CGLIB-proxied {@code @Transactional} bean — the
 * standard enterprise service shape the ticket calls out — must have its initial
 * {@code @Marz} value applied AND a runtime swap visible from a method invoked through
 * the proxy. A no-op {@link PlatformTransactionManager} lets the {@code @Transactional}
 * proxy form without a datasource; spring-tx is a test-only dependency.
 */
@SpringBootTest(classes = MarzTransactionalProxyIntegrationTest.Config.class)
class MarzTransactionalProxyIntegrationTest {

    @Configuration
    @EnableTransactionManagement
    @ImportAutoConfiguration(MarzAutoConfiguration.class)
    static class Config {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean
        TxFeatureService txFeatureService() {
            return new TxFeatureService();
        }
    }

    static class TxFeatureService {
        @Marz(key = "feature.tx.enabled", defaultValue = "false")
        volatile boolean enabled;

        // @Transactional wraps this concrete bean in a CGLIB proxy — the canonical
        // enterprise service shape KAN-96 calls out. Business reads run on the target.
        @Transactional
        public boolean isEnabledTransactionally() {
            return enabled;
        }
    }

    /** No-op transaction manager so the @Transactional proxy works without a datasource. */
    static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) { }

        @Override
        public void rollback(TransactionStatus status) { }
    }

    @Autowired
    TxFeatureService service; // injected as the CGLIB transactional proxy

    @Autowired
    MarzRegistry registry;

    @Test
    @DisplayName("@Transactional CGLIB bean: initial value applies and a runtime swap is visible through the proxy")
    void transactionalProxiedBean_initialApplies_andSwapVisible() {
        assertThat(AopUtils.isCglibProxy(service)).isTrue();

        assertThat(registry.getBindings("feature.tx.enabled")).hasSize(1);
        assertThat(service.isEnabledTransactionally()).isFalse();

        registry.onSourceChange("platform://marz", Map.of("feature.tx.enabled", "true"));
        assertThat(service.isEnabledTransactionally()).isTrue();
    }
}
