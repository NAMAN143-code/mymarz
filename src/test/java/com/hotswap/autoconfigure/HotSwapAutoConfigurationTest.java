package com.hotswap.autoconfigure;

import com.hotswap.core.ConfigSourceFactory;
import com.hotswap.core.ConfigSourcePoller;
import com.hotswap.core.HotSwapBeanPostProcessor;
import com.hotswap.core.HotSwapRegistry;
import com.hotswap.source.ConfigFormatParser;
import com.hotswap.type.TypeCoercer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HotSwapAutoConfiguration} using
 * {@link ApplicationContextRunner} — fast, no real application context.
 */
class HotSwapAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HotSwapAutoConfiguration.class));

    @Test
    @DisplayName("registers all HotSwap beans when enabled (default)")
    void registersAllBeansWhenEnabled() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(HotSwapRegistry.class);
            assertThat(ctx).hasSingleBean(ConfigFormatParser.class);
            assertThat(ctx).hasSingleBean(ConfigSourceFactory.class);
            assertThat(ctx).hasSingleBean(TypeCoercer.class);
            assertThat(ctx).hasSingleBean(HotSwapBeanPostProcessor.class);
            assertThat(ctx).hasSingleBean(ConfigSourcePoller.class);
            assertThat(ctx).hasBean("hotSwapPollerLifecycle");
        });
    }

    @Test
    @DisplayName("does not register any beans when hotswap.enabled=false")
    void noBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("hotswap.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(HotSwapRegistry.class);
                    assertThat(ctx).doesNotHaveBean(ConfigSourcePoller.class);
                    assertThat(ctx).doesNotHaveBean(HotSwapBeanPostProcessor.class);
                    assertThat(ctx).doesNotHaveBean(SmartLifecycle.class);
                });
    }

    @Test
    @DisplayName("uses user-provided HotSwapRegistry when defined")
    void userRegistryTakesPrecedence() {
        HotSwapRegistry customRegistry = new HotSwapRegistry();
        contextRunner
                .withBean(HotSwapRegistry.class, () -> customRegistry)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(HotSwapRegistry.class);
                    assertThat(ctx.getBean(HotSwapRegistry.class)).isSameAs(customRegistry);
                });
    }

    @Test
    @DisplayName("uses user-provided ConfigSourceFactory when defined")
    void userFactoryTakesPrecedence() {
        ConfigSourceFactory customFactory = new ConfigSourceFactory();
        contextRunner
                .withBean(ConfigSourceFactory.class, () -> customFactory)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ConfigSourceFactory.class);
                    assertThat(ctx.getBean(ConfigSourceFactory.class)).isSameAs(customFactory);
                });
    }

    @Test
    @DisplayName("binds hotswap.* properties correctly")
    void bindsProperties() {
        contextRunner
                .withPropertyValues(
                        "hotswap.default-poll-interval-ms=10000",
                        "hotswap.thread-pool-size=4",
                        "hotswap.default-source=file:///etc/app/config.yml"
                )
                .run(ctx -> {
                    HotSwapProperties props = ctx.getBean(HotSwapProperties.class);
                    assertThat(props.getDefaultPollIntervalMs()).isEqualTo(10000L);
                    assertThat(props.getThreadPoolSize()).isEqualTo(4);
                    assertThat(props.getDefaultSource()).isEqualTo("file:///etc/app/config.yml");
                });
    }

    @Test
    @DisplayName("uses default property values when not configured")
    void usesDefaultPropertyValues() {
        contextRunner.run(ctx -> {
            HotSwapProperties props = ctx.getBean(HotSwapProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getDefaultPollIntervalMs()).isEqualTo(5000L);
            assertThat(props.getThreadPoolSize()).isEqualTo(0);
            assertThat(props.getDefaultSource()).isNull();
        });
    }

    @Test
    @DisplayName("ConfigSourceFactory has file:// scheme registered out of the box")
    void fileSchemeRegisteredByDefault() {
        contextRunner.run(ctx -> {
            ConfigSourceFactory factory = ctx.getBean(ConfigSourceFactory.class);
            assertThat(factory.create("file:///tmp/nonexistent-test-config.yml")).isNotNull();
        });
    }
}
