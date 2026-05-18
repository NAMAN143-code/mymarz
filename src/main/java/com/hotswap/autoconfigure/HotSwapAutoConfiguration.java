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
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * Spring Boot auto-configuration for HotSwap.
 *
 * <p>Wires the full HotSwap stack automatically when this starter is on the classpath:</p>
 * <ol>
 *   <li>{@link HotSwapProperties} — bound from {@code application.yml}</li>
 *   <li>{@link HotSwapRegistry} — central field registry</li>
 *   <li>{@link ConfigFormatParser} — YAML/JSON/.properties parser</li>
 *   <li>{@link ConfigSourceFactory} — URI-to-source resolver; {@code file://} registered by default</li>
 *   <li>{@link TypeCoercer} — String → Java type coercion</li>
 *   <li>{@link HotSwapBeanPostProcessor} — scans beans for {@code @HotSwap} at startup</li>
 *   <li>{@link ConfigSourcePoller} — polling scheduler; starts after context is fully refreshed</li>
 * </ol>
 *
 * <p>All beans are conditional on {@code @ConditionalOnMissingBean}, so user-defined
 * beans of the same type always take precedence.</p>
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

    // -------------------------------------------------------------------------
    // Core infrastructure beans
    // -------------------------------------------------------------------------

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
        // Register built-in file:// scheme
        factory.registerCreator("file", uri -> new FileConfigSource(uri, parser));
        log.debug("HotSwap: registered built-in 'file' ConfigSource creator");
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public TypeCoercer typeCoercer() {
        return new TypeCoercer();
    }

    // -------------------------------------------------------------------------
    // BeanPostProcessor — must be registered early so it sees all beans
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public static HotSwapBeanPostProcessor hotSwapBeanPostProcessor(
            HotSwapRegistry registry,
            ConfigSourceFactory factory,
            TypeCoercer coercer) {
        // static @Bean method ensures BPP is registered before other beans are created
        return new HotSwapBeanPostProcessor(registry, factory, coercer);
    }

    // -------------------------------------------------------------------------
    // Poller — started AFTER context is fully refreshed
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public ConfigSourcePoller configSourcePoller(
            HotSwapRegistry registry,
            TypeCoercer coercer,
            ApplicationEventPublisher publisher,
            HotSwapProperties properties) {
        return new ConfigSourcePoller(registry, coercer, publisher, properties.getThreadPoolSize());
    }

    /**
     * Start the poller after the full application context is initialized.
     * Using {@code ContextRefreshedEvent} ensures all beans have been
     * post-processed and all {@code @HotSwap} fields registered before
     * the first poll fires.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        // @EventListener only supports a single parameter (the event itself).
        // Retrieve the poller from the context via the event to avoid that constraint
        // and to also avoid a circular-dependency if this class declares the poller @Bean.
        try {
            ConfigSourcePoller poller = event.getApplicationContext()
                                             .getBean(ConfigSourcePoller.class);
            if (!poller.isRunning()) {
                poller.start();
                HotSwapRegistry registry = event.getApplicationContext()
                                                .getBean(HotSwapRegistry.class);
                log.info("HotSwap poller started after context refresh ({} fields, {} sources)",
                        registry.getFieldCount(), registry.getSourceCount());
            }
        } catch (Exception e) {
            // Poller bean may not be present if hotswap.enabled=false or overridden
            log.debug("HotSwap poller not available at context refresh: {}", e.getMessage());
        }
    }
}
