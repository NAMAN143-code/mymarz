package com.mymarz.autoconfigure;

import com.mymarz.core.ConfigSourceResolver;
import com.mymarz.core.MarzBeanPostProcessor;
import com.mymarz.core.MarzRegistry;
import com.mymarz.core.SelfRegistrar;
import com.mymarz.core.SourceStrategyResolver;
import com.mymarz.platform.PlatformAgent;
import com.mymarz.platform.PlatformHealthIndicator;
import com.mymarz.platform.PlatformMessageHandler;
import com.mymarz.platform.ConfigUpdateHandler;
import com.mymarz.platform.RegistrationHandler;
import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@ConditionalOnProperty(prefix = "marz", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MarzProperties.class)
public class MarzAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MarzAutoConfiguration.class);

    @Bean @ConditionalOnMissingBean
    public TypeCoercer typeCoercer() { return new TypeCoercer(); }

    @Bean @ConditionalOnMissingBean
    public MarzRegistry marzRegistry(ApplicationEventPublisher pub, TypeCoercer coercer) {
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

    @Bean @ConditionalOnMissingBean
    public SelfRegistrar selfRegistrar() {
        return new SelfRegistrar();
    }

    @Bean
    public static MarzBeanPostProcessor marzBeanPostProcessor(
            MarzRegistry registry, TypeCoercer coercer,
            ConfigSourceResolver sourceResolver, SelfRegistrar selfRegistrar) {
        return new MarzBeanPostProcessor(registry, coercer, sourceResolver, selfRegistrar);
    }

    @Bean
    public SmartLifecycle marzSourceLifecycle(ConfigSourceResolver sourceResolver,
                                              MarzRegistry registry,
                                              SelfRegistrar selfRegistrar) {
        return new SmartLifecycle() {
            private volatile boolean running = false;
            @Override public void start() {
                sourceResolver.startAll();
                selfRegistrar.writeAll();
                log.info("MARZ started: {} key(s), {} binding(s)",
                        registry.getRegisteredKeyCount(), registry.getTotalBindingCount());
                running = true;
            }
            @Override public void stop() { sourceResolver.stopAll(); running = false; }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return Integer.MAX_VALUE - 1; }
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // PLATFORM AGENT (conditional on marz.platform.api-key)
    // ═══════════════════════════════════════════════════════════════════

    @Bean
    @ConditionalOnProperty(prefix = "marz.platform", name = "api-key")
    @ConditionalOnMissingBean
    public PlatformAgent platformAgent(MarzProperties properties,
                                        List<PlatformMessageHandler> handlers) {
        return new PlatformAgent(properties.getPlatform(), handlers);
    }

    @Bean
    @ConditionalOnProperty(prefix = "marz.platform", name = "api-key")
    @ConditionalOnMissingBean
    public RegistrationHandler registrationHandler(MarzRegistry registry, PlatformAgent agent,
                                                     MarzProperties properties,
                                                     org.springframework.core.env.Environment env) {
        String appName = env.getProperty("spring.application.name", "unknown");
        String environment = properties.getPlatform().getEnvironment();
        if (environment == null || environment.isEmpty()) {
            String[] profiles = env.getActiveProfiles();
            environment = profiles.length > 0 ? profiles[0] : "default";
        }
        return new RegistrationHandler(registry, agent, properties.getPlatform(), appName, environment);
    }

    @Bean
    @ConditionalOnProperty(prefix = "marz.platform", name = "api-key")
    @ConditionalOnMissingBean
    public ConfigUpdateHandler configUpdateHandler(MarzRegistry registry, PlatformAgent agent,
                                                     MarzProperties properties,
                                                     RegistrationHandler registrationHandler) {
        return new ConfigUpdateHandler(registry, agent,
                properties.getPlatform().getApiKey(), registrationHandler.getInstanceId());
    }

    @Bean
    @ConditionalOnProperty(prefix = "marz.platform", name = "api-key")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(PlatformHealthIndicator.class)
    public PlatformHealthIndicator platformHealthIndicator(PlatformAgent agent, MarzProperties properties) {
        return new PlatformHealthIndicator(agent, properties.getPlatform().getEndpoint());
    }

    @Bean
    @ConditionalOnProperty(prefix = "marz.platform", name = "api-key")
    public SmartLifecycle marzPlatformLifecycle(PlatformAgent agent) {
        return new SmartLifecycle() {
            private volatile boolean running = false;
            @Override public void start() { agent.start(); running = true; }
            @Override public void stop() { agent.stop(); running = false; }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return Integer.MAX_VALUE; }
        };
    }
}
