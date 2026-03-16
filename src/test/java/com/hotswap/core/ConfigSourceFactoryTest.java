package com.hotswap.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigSourceFactoryTest {

    private ConfigSourceFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ConfigSourceFactory();
    }

    @Test
    void extractScheme_fileUri() {
        assertThat(ConfigSourceFactory.extractScheme("file:///etc/config.yml")).isEqualTo("file");
    }

    @Test
    void extractScheme_classpathUri() {
        assertThat(ConfigSourceFactory.extractScheme("classpath:config.properties")).isEqualTo("classpath");
    }

    @Test
    void extractScheme_httpUri() {
        assertThat(ConfigSourceFactory.extractScheme("http://host/path")).isEqualTo("http");
        assertThat(ConfigSourceFactory.extractScheme("https://host/path")).isEqualTo("https");
    }

    @Test
    void extractScheme_platformUri() {
        assertThat(ConfigSourceFactory.extractScheme("platform://hotswap")).isEqualTo("platform");
    }

    @Test
    void extractScheme_empty() {
        assertThat(ConfigSourceFactory.extractScheme("")).isEmpty();
        assertThat(ConfigSourceFactory.extractScheme(null)).isEmpty();
    }

    @Test
    void create_usesRegisteredCreator() {
        ConfigSource mockSource = mock(ConfigSource.class);
        when(mockSource.sourceId()).thenReturn("test-source");

        factory.registerCreator("file", uri -> mockSource);

        ConfigSource result = factory.create("file:///config.yml");
        assertThat(result).isSameAs(mockSource);
    }

    @Test
    void create_cachesResult() {
        ConfigSource mockSource = mock(ConfigSource.class);
        factory.registerCreator("file", uri -> mockSource);

        ConfigSource first = factory.create("file:///config.yml");
        ConfigSource second = factory.create("file:///config.yml");

        assertThat(first).isSameAs(second);
    }

    @Test
    void create_unknownScheme_returnsNull() {
        ConfigSource result = factory.create("ftp://something");
        assertThat(result).isNull();
    }

    @Test
    void create_differentUris_differentInstances() {
        factory.registerCreator("file", uri -> {
            ConfigSource source = mock(ConfigSource.class);
            when(source.sourceId()).thenReturn(uri);
            return source;
        });

        ConfigSource a = factory.create("file:///a.yml");
        ConfigSource b = factory.create("file:///b.yml");

        assertThat(a).isNotSameAs(b);
    }
}
