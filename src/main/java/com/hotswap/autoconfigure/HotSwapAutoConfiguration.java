package com.hotswap.autoconfigure;

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
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for HotSwap.
 *
 * <p>Registers the core beans: {@link HotSwapRegistry} (reverse index),
 * {@link TypeCoercer}, {@link HotSwapBeanPostProcessor}, and
 * {@link ConfigFormatParser}.</p>
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
        log.debug("HotSwap: creating registry with reverse index");
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

    /**
     * Static @Bean ensures the BPP is registered before other beans.
     */
    @Bean
    public static HotSwapBeanPostProcessor hotSwapBeanPostProcessor(
            HotSwapRegistry registry,
            TypeCoercer coercer) {
        return new HotSwapBeanPostProcessor(registry, coercer);
    }
}
