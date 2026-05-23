package com.hotswap.core;

import com.hotswap.source.ConfigFormatParser;
import com.hotswap.type.TypeCoercer;
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
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TypeCoercer coercer = new TypeCoercer();
        HotSwapRegistry registry = new HotSwapRegistry(publisher, coercer);
        ConfigFormatParser parser = new ConfigFormatParser();
        SourceStrategyResolver strategyResolver = new SourceStrategyResolver();

        resolver = new ConfigSourceResolver(parser, registry, strategyResolver);
    }

    @Test
    void extractScheme_file() {
        assertThat(ConfigSourceResolver.extractScheme("file:///config.yml")).isEqualTo("file");
    }

    @Test
    void extractScheme_http() {
        assertThat(ConfigSourceResolver.extractScheme("http://host/config")).isEqualTo("http");
        assertThat(ConfigSourceResolver.extractScheme("https://host/config")).isEqualTo("https");
    }

    @Test
    void extractScheme_platform() {
        assertThat(ConfigSourceResolver.extractScheme("platform://hotswap")).isEqualTo("platform");
    }

    @Test
    void extractScheme_empty() {
        assertThat(ConfigSourceResolver.extractScheme("")).isEmpty();
        assertThat(ConfigSourceResolver.extractScheme(null)).isEmpty();
    }

    @Test
    void resolve_fileSource_returnsConfigSource(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value\n");

        ConfigSource source = resolver.resolve("file://" + configFile.toAbsolutePath());

        assertThat(source).isNotNull();
        assertThat(source.scheme()).isEqualTo("file");
        assertThat(source.resolve("key")).isEqualTo("value");
    }

    @Test
    void resolve_httpSource_returnsConfigSource() {
        ConfigSource source = resolver.resolve("http://localhost:19999/config.json");

        assertThat(source).isNotNull();
        assertThat(source.scheme()).isEqualTo("http");
    }

    @Test
    void resolve_platformSource_returnsNull() {
        ConfigSource source = resolver.resolve("platform://hotswap");
        assertThat(source).isNull();
    }

    @Test
    void resolve_unknownScheme_returnsNull() {
        ConfigSource source = resolver.resolve("ftp://something");
        assertThat(source).isNull();
    }

    @Test
    void resolve_cachesResult(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value\n");

        String uri = "file://" + configFile.toAbsolutePath();
        ConfigSource first = resolver.resolve(uri);
        ConfigSource second = resolver.resolve(uri);

        assertThat(first).isSameAs(second);
    }

    @Test
    void getAllSources_returnsCreatedSources(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value\n");

        resolver.resolve("file://" + configFile.toAbsolutePath());
        resolver.resolve("http://localhost:19999/config.json");

        assertThat(resolver.getAllSources()).hasSize(2);
    }
}
