package com.hotswap.source;

import com.hotswap.core.HotSwapRegistry;
import com.hotswap.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HttpConfigSourceTest {

    private HotSwapRegistry registry;
    private ConfigFormatParser parser;

    @BeforeEach
    void setUp() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TypeCoercer coercer = new TypeCoercer();
        registry = new HotSwapRegistry(publisher, coercer);
        parser = new ConfigFormatParser();
    }

    @Test
    void backoff_exponential_capped() {
        // Create source pointing to unreachable endpoint — initial load will fail silently
        HttpConfigSource source = new HttpConfigSource(
                "http://localhost:19999/config.json", parser, registry, 5);

        assertThat(source.calculateBackoff(5)).isEqualTo(5000L);   // 5s * 2^0
        assertThat(source.calculateBackoff(6)).isEqualTo(10000L);  // 5s * 2^1
        assertThat(source.calculateBackoff(7)).isEqualTo(20000L);  // 5s * 2^2
        assertThat(source.calculateBackoff(8)).isEqualTo(40000L);  // 5s * 2^3
        assertThat(source.calculateBackoff(9)).isEqualTo(60000L);  // Capped at 60s
        assertThat(source.calculateBackoff(20)).isEqualTo(60000L); // Still capped
    }

    @Test
    void scheme_http() {
        HttpConfigSource source = new HttpConfigSource(
                "http://config-server/api/v1/config", parser, registry, 5);
        assertThat(source.scheme()).isEqualTo("http");
    }

    @Test
    void scheme_https() {
        HttpConfigSource source = new HttpConfigSource(
                "https://config-server/api/v1/config", parser, registry, 5);
        assertThat(source.scheme()).isEqualTo("https");
    }

    @Test
    void sourceId_matchesUri() {
        String uri = "http://config-server:8080/api/config";
        HttpConfigSource source = new HttpConfigSource(uri, parser, registry, 5);
        assertThat(source.sourceId()).isEqualTo(uri);
    }

    @Test
    void initialState_emptyWhenUnreachable() {
        HttpConfigSource source = new HttpConfigSource(
                "http://localhost:19999/nonexistent", parser, registry, 5);
        assertThat(source.getCachedState()).isEmpty();
        assertThat(source.getLastEtag()).isNull();
    }

    @Test
    void startAndStop_lifecycle() {
        HttpConfigSource source = new HttpConfigSource(
                "http://localhost:19999/config.json", parser, registry, 5);

        assertThat(source.isRunning()).isFalse();
        source.start();
        assertThat(source.isRunning()).isTrue();
        source.stop();
        assertThat(source.isRunning()).isFalse();
    }

    @Test
    void consecutiveFailures_initiallyZero() {
        HttpConfigSource source = new HttpConfigSource(
                "http://localhost:19999/config.json", parser, registry, 5);
        assertThat(source.getConsecutiveFailures()).isZero();
    }
}
