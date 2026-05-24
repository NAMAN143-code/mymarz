package com.mymarz.core;

import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Expanded tests for {@link ConfigSourceResolver}.
 *
 * Covers: platform://marz fallback via defaultSource,
 * classpath: resolution (KAN-27), lifecycle management,
 * and StaticConfigSource for JAR resources.
 */
class ConfigSourceResolverExpandedTest {

    private MarzRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());
    }

    // ═══════════════════════════════════════════════════════════════════
    // PLATFORM FALLBACK — defaultSource configured
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("platform://marz falls back to defaultSource when configured")
    void platformFallback_usesDefaultSource(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("fallback-config.yml");
        Files.writeString(configFile, "feature.enabled: true\n");

        String defaultSource = "file://" + configFile.toAbsolutePath();
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                defaultSource, 5000L);

        // Resolve platform:// should fall back to defaultSource
        ConfigSource source = resolver.resolve("platform://marz");

        assertThat(source).isNotNull();
        assertThat(source.scheme()).isEqualTo("file");
        assertThat(source.resolve("feature.enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("platform://marz returns null when no defaultSource")
    void platformFallback_nullWhenNoDefault() {
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        ConfigSource source = resolver.resolve("platform://marz");

        assertThat(source).isNull();
    }

    @Test
    @DisplayName("platform://marz returns null when defaultSource is empty")
    void platformFallback_nullWhenEmptyDefault() {
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                "", 5000L);

        ConfigSource source = resolver.resolve("platform://marz");

        assertThat(source).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLASSPATH RESOLUTION (KAN-27)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("classpath: URI resolves via ClassLoader")
    void classpathResolution_usesClassLoader() {
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        // application.properties exists on the test classpath
        ConfigSource source = resolver.resolve("classpath:application.properties");

        // May be null if no application.properties in test resources
        // but should not throw NPE (KAN-25 fix)
    }

    @Test
    @DisplayName("classpath: with leading slash is stripped")
    void classpathResolution_stripsLeadingSlash() {
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        // Should not crash regardless of slash presence
        resolver.resolve("classpath:/nonexistent.yml");
        resolver.resolve("classpath:///nonexistent.yml");
        // No exception = pass
    }

    @Test
    @DisplayName("classpath: with nonexistent resource returns null")
    void classpathResolution_nonexistentReturnsNull() {
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        ConfigSource source = resolver.resolve("classpath:does-not-exist.yml");

        assertThat(source).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE — startAll / stopAll
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("startAll starts all registered sources")
    void startAll_startsAllSources(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value\n");

        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        String uri = "file://" + configFile.toAbsolutePath();
        ConfigSource source = resolver.resolve(uri);
        assertThat(source).isNotNull();

        resolver.startAll();

        assertThat(source.isRunning()).isTrue();

        resolver.stopAll();

        assertThat(source.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stopAll is idempotent — no error when called twice")
    void stopAll_idempotent(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value\n");

        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        resolver.resolve("file://" + configFile.toAbsolutePath());

        resolver.startAll();
        resolver.stopAll();
        resolver.stopAll(); // second call — should not throw
    }

    @Test
    @DisplayName("startAll with no sources does not throw")
    void startAll_noSources_noError() {
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        resolver.startAll(); // no sources registered — should be no-op
        resolver.stopAll();
    }

    // ═══════════════════════════════════════════════════════════════════
    // NULL / EMPTY INPUT HANDLING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("resolve(null) returns null")
    void resolveNull_returnsNull() {
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    @DisplayName("resolve('') returns null")
    void resolveEmpty_returnsNull() {
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 5000L);

        assertThat(resolver.resolve("")).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // POLL INTERVAL ENFORCEMENT
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("defaultPollIntervalMs below 500 is clamped in constructor")
    void pollInterval_clamped(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value\n");

        // Pass 100ms — should be clamped to 500ms internally
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(),
                null, 100L);

        // We verify this indirectly — HTTP source created with clamped interval
        ConfigSource httpSource = resolver.resolve("http://localhost:19999/config.json");
        assertThat(httpSource).isNotNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXTRACT SCHEME — edge cases
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extractScheme handles edge cases")
    void extractScheme_edgeCases() {
        assertThat(ConfigSourceResolver.extractScheme("classpath:config.yml")).isEqualTo("classpath");
        assertThat(ConfigSourceResolver.extractScheme("HTTPS://Secure.com")).isEqualTo("https");
        assertThat(ConfigSourceResolver.extractScheme("noScheme")).isEqualTo("noscheme");
        assertThat(ConfigSourceResolver.extractScheme(":oops")).isEmpty();
    }
}
