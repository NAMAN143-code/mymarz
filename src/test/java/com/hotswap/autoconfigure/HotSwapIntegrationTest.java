package com.hotswap.autoconfigure;

import com.hotswap.annotation.HotSwap;
import com.hotswap.core.ConfigSourceResolver;
import com.hotswap.core.HotSwapRegistry;
import com.hotswap.source.ConfigFormatParser;
import com.hotswap.type.TypeCoercer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HotSwapIntegrationTest.TestConfig.class)
class HotSwapIntegrationTest {

    @Configuration
    @ImportAutoConfiguration(HotSwapAutoConfiguration.class)
    static class TestConfig {
        @Component
        static class SampleBean {
            @HotSwap(key = "feature.checkout.enabled", defaultValue = "true")
            boolean checkoutEnabled = false;

            @HotSwap(key = "rate.limit.max", defaultValue = "100")
            int maxRate = 0;
        }
    }

    @Autowired HotSwapRegistry registry;
    @Autowired TypeCoercer typeCoercer;
    @Autowired ConfigFormatParser parser;
    @Autowired ConfigSourceResolver sourceResolver;

    @Nested @DisplayName("Context startup")
    class ContextStartup {

        @Test @DisplayName("all core beans wired")
        void allBeansWired() {
            assertThat(registry).isNotNull();
            assertThat(typeCoercer).isNotNull();
            assertThat(parser).isNotNull();
            assertThat(sourceResolver).isNotNull();
        }

        @Test @DisplayName("BPP registers fields in reverse index")
        void fieldsRegistered() {
            assertThat(registry.getRegisteredKeyCount()).isEqualTo(2);
            assertThat(registry.getTotalBindingCount()).isEqualTo(2);
        }

        @Test @DisplayName("initial values from defaultValue")
        void initialValues() {
            assertThat(registry.getBindings("feature.checkout.enabled").get(0).ref().get()).isEqualTo(true);
            assertThat(registry.getBindings("rate.limit.max").get(0).ref().get()).isEqualTo(100);
        }
    }
}
