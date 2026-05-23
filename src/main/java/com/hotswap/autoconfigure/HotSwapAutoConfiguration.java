package com.hotswap.autoconfigure;

import com.hotswap.core.ConfigSourceResolver;
import com.hotswap.core.HotSwapBeanPostProcessor;
import com.hotswap.core.HotSwapRegistry;
import com.hotswap.core.SourceStrategyResolver;
import com.hotswap.source.ConfigFormatParser;
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
 * <p>Registers all core beans and manages the lifecycle of config sources
 * (file watchers, HTTP pollers) via {@link SmartLifecycle}. Sources are
 * started AFTER all beans are post-processed, ensuring the reverse index
 * is fully populated before change detection begins.</p>
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
    public TypeCoercer typeCoercer() {
        return new TypeCoercer();
    }

    @Bean
    @ConditionalOnMissingBean
    public HotSwapRegistry hotSwapRegistry(ApplicationEventPublisher eventPublisher, TypeCoercer typeCoercer) {
        return new HotSwapRegistry(eventPublisher, typeCoercer);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigFormatParser configFormatParser() {
        return new ConfigFormatParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public SourceStrategyResolver sourceStrategyResolver() {
        return new SourceStrategyResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigSourceResolver configSourceResolver(ConfigFormatParser parser,
                                                      HotSwapRegistry registry,
                                                      SourceStrategyResolver strategyResolver) {
        return new ConfigSourceResolver(parser, registry, strategyResolver);
    }

    /**
     * Static @Bean ensures the BPP is registered before other beans.
     */
    @Bean
    public static HotSwapBeanPostProcessor hotSwapBeanPostProcessor(
            HotSwapRegistry registry,
            TypeCoercer coercer,
            ConfigSourceResolver sourceResolver) {
        return new HotSwapBeanPostProcessor(registry, coercer, sourceResolver);
    }

    /**
     * SmartLifecycle starts all config sources AFTER all beans are
     * post-processed, ensuring the reverse index is fully populated.
     * On shutdown, all sources are stopped cleanly.
     */
    @Bean
    public SmartLifecycle hotSwapSourceLifecycle(ConfigSourceResolver sourceResolver, HotSwapRegistry registry) {
        return new SmartLifecycle() {
            private volatile boolean running = false;

            @Override
            public void start() {
                sourceResolver.startAll();
                log.info("HotSwap started: {} key(s), {} binding(s)",
                        registry.getRegisteredKeyCount(), registry.getTotalBindingCount());
                running = true;
            }

            @Override
            public void stop() {
                sourceResolver.stopAll();
                running = false;
            }

            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return Integer.MAX_VALUE; }
        };
    }
}
