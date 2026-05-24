package com.mymarz.core;

import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConfigSourceResolverTest {

    private ConfigSourceResolver resolver;

    @BeforeEach
    void setUp() {
        MarzRegistry registry = new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());
        resolver = new ConfigSourceResolver(new ConfigFormatParser(), registry, new SourceStrategyResolver(), null, 5000L);
    }

    @Test void extractScheme_file() { assertThat(ConfigSourceResolver.extractScheme("file:///c.yml")).isEqualTo("file"); }
    @Test void extractScheme_http() { assertThat(ConfigSourceResolver.extractScheme("http://h/c")).isEqualTo("http"); }
    @Test void extractScheme_https() { assertThat(ConfigSourceResolver.extractScheme("https://h/c")).isEqualTo("https"); }
    @Test void extractScheme_platform() { assertThat(ConfigSourceResolver.extractScheme("platform://marz")).isEqualTo("platform"); }
    @Test void extractScheme_empty() { assertThat(ConfigSourceResolver.extractScheme("")).isEmpty(); }
    @Test void extractScheme_null() { assertThat(ConfigSourceResolver.extractScheme(null)).isEmpty(); }

    @Test void resolve_fileSource(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, "key: value\n");
        ConfigSource source = resolver.resolve("file://" + f.toAbsolutePath());
        assertThat(source).isNotNull();
        assertThat(source.scheme()).isEqualTo("file");
        assertThat(source.resolve("key")).isEqualTo("value");
    }

    @Test void resolve_httpSource() {
        ConfigSource source = resolver.resolve("http://localhost:19999/config.json");
        assertThat(source).isNotNull();
        assertThat(source.scheme()).isEqualTo("http");
    }

    @Test void resolve_platformSource_returnsNull() {
        assertThat(resolver.resolve("platform://marz")).isNull();
    }

    @Test void resolve_unknownScheme_returnsNull() {
        assertThat(resolver.resolve("ftp://something")).isNull();
    }

    @Test void resolve_caches(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, "k: v\n");
        String uri = "file://" + f.toAbsolutePath();
        assertThat(resolver.resolve(uri)).isSameAs(resolver.resolve(uri));
    }

    @Test void getAllSources(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.yml");
        Files.writeString(f, "k: v\n");
        resolver.resolve("file://" + f.toAbsolutePath());
        resolver.resolve("http://localhost:19999/config.json");
        assertThat(resolver.getAllSources()).hasSize(2);
    }
}
