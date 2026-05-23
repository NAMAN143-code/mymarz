package com.hotswap.autoconfigure;

import com.hotswap.core.ConfigSourceResolver;
import com.hotswap.core.HotSwapBeanPostProcessor;
import com.hotswap.core.HotSwapRegistry;
import com.hotswap.core.SourceStrategyResolver;
import com.hotswap.source.ConfigFormatParser;
import com.hotswap.type.TypeCoercer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class HotSwapAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HotSwapAutoConfiguration.class));

    @Test @DisplayName("all core beans created by default")
    void defaultBeansCreated() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(HotSwapRegistry.class);
            assertThat(ctx).hasSingleBean(TypeCoercer.class);
            assertThat(ctx).hasSingleBean(ConfigFormatParser.class);
            assertThat(ctx).hasSingleBean(SourceStrategyResolver.class);
            assertThat(ctx).hasSingleBean(ConfigSourceResolver.class);
            assertThat(ctx).hasSingleBean(HotSwapBeanPostProcessor.class);
        });
    }

    @Test @DisplayName("disabled when hotswap.enabled=false")
    void disabledByProperty() {
        runner.withPropertyValues("hotswap.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(HotSwapRegistry.class);
                    assertThat(ctx).doesNotHaveBean(ConfigSourceResolver.class);
                });
    }

    @Test @DisplayName("SmartLifecycle bean exists")
    void lifecycleBeanExists() {
        runner.run(ctx -> assertThat(ctx).hasBean("hotSwapSourceLifecycle"));
    }
}
