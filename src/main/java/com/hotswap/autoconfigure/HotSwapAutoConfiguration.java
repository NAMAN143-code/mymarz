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

@AutoConfiguration
@ConditionalOnProperty(prefix = "hotswap", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HotSwapProperties.class)
public class HotSwapAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HotSwapAutoConfiguration.class);

    @Bean @ConditionalOnMissingBean
    public TypeCoercer typeCoercer() { return new TypeCoercer(); }

    @Bean @ConditionalOnMissingBean
    public HotSwapRegistry hotSwapRegistry(ApplicationEventPublisher pub, TypeCoercer coercer) {
        return new HotSwapRegistry(pub, coercer);
    }

    @Bean @ConditionalOnMissingBean
    public ConfigFormatParser configFormatParser() { return new ConfigFormatParser(); }

    @Bean @ConditionalOnMissingBean
    public SourceStrategyResolver sourceStrategyResolver() { return new SourceStrategyResolver(); }

    @Bean @ConditionalOnMissingBean
    public ConfigSourceResolver configSourceResolver(ConfigFormatParser parser, HotSwapRegistry registry,
                                                      SourceStrategyResolver strategyResolver,
                                                      HotSwapProperties properties) {
        return new ConfigSourceResolver(parser, registry, strategyResolver,
                properties.getDefaultSource(), properties.getDefaultPollIntervalMs());
    }

    @Bean
    public static HotSwapBeanPostProcessor hotSwapBeanPostProcessor(
            HotSwapRegistry registry, TypeCoercer coercer, ConfigSourceResolver sourceResolver) {
        return new HotSwapBeanPostProcessor(registry, coercer, sourceResolver);
    }

    @Bean
    public SmartLifecycle hotSwapSourceLifecycle(ConfigSourceResolver sourceResolver, HotSwapRegistry registry) {
        return new SmartLifecycle() {
            private volatile boolean running = false;
            @Override public void start() {
                sourceResolver.startAll();
                log.info("HotSwap started: {} key(s), {} binding(s)",
                        registry.getRegisteredKeyCount(), registry.getTotalBindingCount());
                running = true;
            }
            @Override public void stop() { sourceResolver.stopAll(); running = false; }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return Integer.MAX_VALUE; }
        };
    }
}
