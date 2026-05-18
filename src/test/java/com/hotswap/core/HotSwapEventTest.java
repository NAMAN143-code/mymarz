package com.hotswap.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HotSwapEventTest {

    @Test
    void eventCarriesAllFields() {
        Object bean = new Object();
        HotSwapEvent event = new HotSwapEvent(bean, "feature.enabled", false, true, "file:///config.yml");

        assertThat(event.getKey()).isEqualTo("feature.enabled");
        assertThat(event.getOldValue()).isEqualTo(false);
        assertThat(event.getNewValue()).isEqualTo(true);
        assertThat(event.getConfigSource()).isEqualTo("file:///config.yml");
        assertThat(event.getSource()).isSameAs(bean);
        assertThat(event.getTimestamp()).isGreaterThan(0);
    }

    @Test
    void toStringContainsKeyAndValues() {
        HotSwapEvent event = new HotSwapEvent(this, "rate.limit", 100, 200, "classpath:config.properties");
        String str = event.toString();

        assertThat(str).contains("rate.limit");
        assertThat(str).contains("100");
        assertThat(str).contains("200");
    }
}
