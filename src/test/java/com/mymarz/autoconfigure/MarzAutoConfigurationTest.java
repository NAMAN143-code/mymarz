package com.mymarz.autoconfigure;

import com.mymarz.core.ConfigSourceResolver;
import com.mymarz.core.MarzBeanPostProcessor;
import com.mymarz.core.MarzRegistry;
import com.mymarz.core.SelfRegistrar;
import com.mymarz.core.SourceStrategyResolver;
import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MarzAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MarzAutoConfiguration.class));

    @Test @DisplayName("all core beans created by default")
    void defaultBeansCreated() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MarzRegistry.class);
            assertThat(ctx).hasSingleBean(TypeCoercer.class);
            assertThat(ctx).hasSingleBean(ConfigFormatParser.class);
            assertThat(ctx).hasSingleBean(SourceStrategyResolver.class);
            assertThat(ctx).hasSingleBean(ConfigSourceResolver.class);
            assertThat(ctx).hasSingleBean(SelfRegistrar.class);
            assertThat(ctx).hasSingleBean(MarzBeanPostProcessor.class);
        });
    }

    @Test @DisplayName("disabled when marz.enabled=false")
    void disabledByProperty() {
        runner.withPropertyValues("marz.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(MarzRegistry.class);
                    assertThat(ctx).doesNotHaveBean(ConfigSourceResolver.class);
                });
    }

    @Test @DisplayName("SmartLifecycle bean exists")
    void lifecycleBeanExists() {
        runner.run(ctx -> assertThat(ctx).hasBean("hotSwapSourceLifecycle"));
    }
}
