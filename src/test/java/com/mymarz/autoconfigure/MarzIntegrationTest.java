package com.mymarz.autoconfigure;

import com.mymarz.annotation.MARZ;
import com.mymarz.core.ConfigSourceResolver;
import com.mymarz.core.MarzRegistry;
import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MarzIntegrationTest.TestConfig.class)
class MarzIntegrationTest {

    @Configuration
    @ImportAutoConfiguration(MarzAutoConfiguration.class)
    static class TestConfig {
        @Component
        static class SampleBean {
            @Marz(key = "feature.checkout.enabled", defaultValue = "true")
            volatile boolean checkoutEnabled = false;

            @Marz(key = "rate.limit.max", defaultValue = "100")
            volatile int maxRate = 0;
        }
    }

    @Autowired MarzRegistry registry;
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
