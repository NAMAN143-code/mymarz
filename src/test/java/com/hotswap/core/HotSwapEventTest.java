package com.hotswap.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HotSwapEventTest {

    @Test
    void eventCarriesAllFields() {
        Object bean = new Object();
        HotSwapEvent event = new HotSwapEvent(bean, "feature.enabled", false, true, "file:///config.yml", false);

        assertThat(event.getKey()).isEqualTo("feature.enabled");
        assertThat(event.getOldValue()).isEqualTo(false);
        assertThat(event.getNewValue()).isEqualTo(true);
        assertThat(event.getConfigSource()).isEqualTo("file:///config.yml");
        assertThat(event.isSensitive()).isFalse();
        assertThat(event.getSource()).isSameAs(bean);
    }

    @Test
    void toStringContainsKeyAndValues_whenNotSensitive() {
        HotSwapEvent event = new HotSwapEvent(this, "rate.limit", 100, 200, "classpath:config.properties", false);
        String str = event.toString();

        assertThat(str).contains("rate.limit");
        assertThat(str).contains("100");
        assertThat(str).contains("200");
    }

    @Test
    void toStringMasksValues_whenSensitive() {
        HotSwapEvent event = new HotSwapEvent(this, "secrets.api-key", "old-secret", "new-secret", "file:///config.yml", true);
        String str = event.toString();

        assertThat(str).contains("secrets.api-key");
        assertThat(str).contains("***");
        assertThat(str).doesNotContain("old-secret");
        assertThat(str).doesNotContain("new-secret");
    }

    @Test
    void sensitiveEvent_gettersStillReturnRawValues() {
        // Callers who NEED the raw value can still get it — masking is only on toString()
        HotSwapEvent event = new HotSwapEvent(this, "secrets.key", "old", "new", "file:///c.yml", true);

        assertThat(event.getOldValue()).isEqualTo("old");
        assertThat(event.getNewValue()).isEqualTo("new");
        assertThat(event.isSensitive()).isTrue();
    }
}
