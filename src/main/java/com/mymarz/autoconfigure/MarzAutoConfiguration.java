package com.mymarz.autoconfigure;

import com.mymarz.core.ConfigSourceResolver;
import com.mymarz.core.MarzBeanPostProcessor;
import com.mymarz.core.MarzRegistry;
import com.mymarz.core.SourceStrategyResolver;
import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
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
@ConditionalOnProperty(prefix = "marz", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MarzProperties.class)
public class MarzAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MarzAutoConfiguration.class);

    @Bean @ConditionalOnMissingBean
    public TypeCoercer typeCoercer() { return new TypeCoercer(); }

    @Bean @ConditionalOnMissingBean
    public MarzRegistry hotSwapRegistry(ApplicationEventPublisher pub, TypeCoercer coercer) {
        return new MarzRegistry(pub, coercer);
    }

    @Bean @ConditionalOnMissingBean
    public ConfigFormatParser configFormatParser() { return new ConfigFormatParser(); }

    @Bean @ConditionalOnMissingBean
    public SourceStrategyResolver sourceStrategyResolver() { return new SourceStrategyResolver(); }

    @Bean @ConditionalOnMissingBean
    public ConfigSourceResolver configSourceResolver(ConfigFormatParser parser, MarzRegistry registry,
                                                      SourceStrategyResolver strategyResolver,
                                                      MarzProperties properties) {
        return new ConfigSourceResolver(parser, registry, strategyResolver,
                properties.getDefaultSource(), properties.getSafetyNetIntervalMs());
    }

    @Bean
    public static MarzBeanPostProcessor hotSwapBeanPostProcessor(
            MarzRegistry registry, TypeCoercer coercer, ConfigSourceResolver sourceResolver) {
        return new MarzBeanPostProcessor(registry, coercer, sourceResolver);
    }

    @Bean
    public SmartLifecycle hotSwapSourceLifecycle(ConfigSourceResolver sourceResolver, MarzRegistry registry) {
        return new SmartLifecycle() {
            private volatile boolean running = false;
            @Override public void start() {
                sourceResolver.startAll();
                log.info("MARZ started: {} key(s), {} binding(s)",
                        registry.getRegisteredKeyCount(), registry.getTotalBindingCount());
                running = true;
            }
            @Override public void stop() { sourceResolver.stopAll(); running = false; }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return Integer.MAX_VALUE; }
        };
    }
}
