package com.hotswap.autoconfigure;

import com.hotswap.core.ConfigSourceFactory;
import com.hotswap.core.ConfigSourcePoller;
import com.hotswap.core.HotSwapBeanPostProcessor;
import com.hotswap.core.HotSwapRegistry;
import com.hotswap.source.ConfigFormatParser;
import com.hotswap.source.FileConfigSource;
import com.hotswap.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for HotSwap.
 *
 * <p>Disabled entirely when {@code hotswap.enabled=false}.</p>
 *
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "hotswap", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HotSwapProperties.class)
public class HotSwapAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HotSwapAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public HotSwapRegistry hotSwapRegistry() {
        return new HotSwapRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigFormatParser configFormatParser() {
        return new ConfigFormatParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigSourceFactory configSourceFactory(ConfigFormatParser parser) {
        ConfigSourceFactory factory = new ConfigSourceFactory();
        factory.registerCreator("file", uri -> new FileConfigSource(uri, parser));
        log.debug("HotSwap: registered built-in 'file' ConfigSource creator");
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public TypeCoercer typeCoercer() {
        return new TypeCoercer();
    }

    /**
     * Static @Bean ensures the BPP is registered before other beans.
     * Intentionally omits @ConditionalOnMissingBean — condition evaluation
     * on static BPP methods in @AutoConfiguration is unreliable across
     * Spring Boot 3.x minor versions.
     */
    @Bean
    public static HotSwapBeanPostProcessor hotSwapBeanPostProcessor(
            HotSwapRegistry registry,
            ConfigSourceFactory factory,
            TypeCoercer coercer) {
        return new HotSwapBeanPostProcessor(registry, factory, coercer);
    }

    /**
     * destroyMethod = "shutdown" ensures the ScheduledExecutorService is
     * cleaned up when the context closes (prevents thread leaks in tests).
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public ConfigSourcePoller configSourcePoller(
            HotSwapRegistry registry,
            TypeCoercer coercer,
            ApplicationEventPublisher publisher,
            HotSwapProperties properties) {
        return new ConfigSourcePoller(registry, coercer, publisher, properties.getThreadPoolSize());
    }

    /**
     * SmartLifecycle starts the poller AFTER all beans are post-processed.
     * Replaces the earlier @EventListener approach which had parameter
     * constraint issues and fragile getBean() lookups.
     */
    @Bean
    public SmartLifecycle hotSwapPollerLifecycle(ConfigSourcePoller poller, HotSwapRegistry registry) {
        return new SmartLifecycle() {
            private volatile boolean running = false;

            @Override public void start() {
                if (!poller.isRunning()) {
                    poller.start();
                    log.info("HotSwap poller started ({} fields, {} sources)",
                            registry.getFieldCount(), registry.getSourceCount());
                }
                running = true;
            }

            @Override public void stop() { running = false; }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return Integer.MAX_VALUE; }
        };
    }
}
