package com.mymarz.source;

import com.mymarz.core.MarzRegistry;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HttpConfigSourceTest {

    private MarzRegistry registry;
    private ConfigFormatParser parser;

    @BeforeEach
    void setUp() {
        registry = new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());
        parser = new ConfigFormatParser();
    }

    @Test
    void backoff_exponential_capped() {
        HttpConfigSource source = new HttpConfigSource("http://localhost:19999/config.json", parser, registry, 5);
        assertThat(source.calculateBackoff(5)).isEqualTo(5000L);
        assertThat(source.calculateBackoff(6)).isEqualTo(10000L);
        assertThat(source.calculateBackoff(7)).isEqualTo(20000L);
        assertThat(source.calculateBackoff(8)).isEqualTo(40000L);
        assertThat(source.calculateBackoff(9)).isEqualTo(60000L);
        assertThat(source.calculateBackoff(20)).isEqualTo(60000L);
    }

    @Test void scheme_http() {
        assertThat(new HttpConfigSource("http://host/cfg", parser, registry, 5).scheme()).isEqualTo("http");
    }

    @Test void scheme_https() {
        assertThat(new HttpConfigSource("https://host/cfg", parser, registry, 5).scheme()).isEqualTo("https");
    }

    @Test void sourceId_matchesUri() {
        String uri = "http://host:8080/api/config";
        assertThat(new HttpConfigSource(uri, parser, registry, 5).sourceId()).isEqualTo(uri);
    }

    @Test void initialState_emptyWhenUnreachable() {
        HttpConfigSource source = new HttpConfigSource("http://localhost:19999/x", parser, registry, 5);
        assertThat(source.getCachedState()).isEmpty();
        assertThat(source.getLastEtag()).isNull();
    }

    @Test void lifecycle() {
        HttpConfigSource source = new HttpConfigSource("http://localhost:19999/x", parser, registry, 5);
        assertThat(source.isRunning()).isFalse();
        source.start();
        assertThat(source.isRunning()).isTrue();
        source.stop();
        assertThat(source.isRunning()).isFalse();
    }

    @Test void consecutiveFailures_initiallyZero() {
        assertThat(new HttpConfigSource("http://localhost:19999/x", parser, registry, 5)
                .getConsecutiveFailures()).isZero();
    }
}
