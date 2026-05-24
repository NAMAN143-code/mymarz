package com.mymarz.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarzEventTest {

    @Test
    void eventCarriesAllFields() {
        Object bean = new Object();
        MarzEvent event = new MarzEvent(bean, "feature.enabled", false, true, "file:///config.yml", false);

        assertThat(event.getKey()).isEqualTo("feature.enabled");
        assertThat(event.getOldValue()).isEqualTo(false);
        assertThat(event.getNewValue()).isEqualTo(true);
        assertThat(event.getConfigSource()).isEqualTo("file:///config.yml");
        assertThat(event.isSensitive()).isFalse();
        assertThat(event.getSource()).isSameAs(bean);
    }

    @Test
    void toStringContainsKeyAndValues() {
        MarzEvent event = new MarzEvent(this, "rate.limit", 100, 200, "classpath:config.properties", false);
        String str = event.toString();

        assertThat(str).contains("rate.limit");
        assertThat(str).contains("100");
        assertThat(str).contains("200");
    }

    @Test
    void sensitiveEvent_masksValuesInToString() {
        MarzEvent event = new MarzEvent(this, "secrets.api-key",
                "old-secret-123", "new-secret-456", "file:///secrets.yml", true);
        String str = event.toString();

        assertThat(event.isSensitive()).isTrue();
        // Values should be masked
        assertThat(str).contains("***");
        assertThat(str).doesNotContain("old-secret-123");
        assertThat(str).doesNotContain("new-secret-456");
        // Key and source should still be visible
        assertThat(str).contains("secrets.api-key");
        assertThat(str).contains("file:///secrets.yml");
    }

    @Test
    void sensitiveEvent_stillExposesRawValuesViaGetters() {
        // Getters expose real values — the masking is only for toString()/logging
        MarzEvent event = new MarzEvent(this, "secrets.key",
                "old-val", "new-val", "file:///config.yml", true);

        assertThat(event.getOldValue()).isEqualTo("old-val");
        assertThat(event.getNewValue()).isEqualTo("new-val");
    }

    @Test
    void nonSensitiveEvent_showsValuesInToString() {
        MarzEvent event = new MarzEvent(this, "feature.flag",
                false, true, "file:///config.yml", false);
        String str = event.toString();

        assertThat(str).contains("false");
        assertThat(str).contains("true");
        assertThat(str).doesNotContain("***");
    }
}
