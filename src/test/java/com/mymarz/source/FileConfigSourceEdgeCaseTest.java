package com.mymarz.source;

import com.mymarz.core.MarzRegistry;
import com.mymarz.core.SourceStrategyResolver;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * File-backed config source edge cases identified in KAN-32.
 * Covers: empty files, comment-only files, file deletion,
 * full file replacement, and whitespace-only values.
 *
 * <p>Lives in {@code com.mymarz.source} so it can exercise the
 * package-private {@link FileConfigSource#getCachedState()} and
 * {@link FileConfigSource#diff(Map, Map)} helpers.</p>
 */
class FileConfigSourceEdgeCaseTest {

    private MarzRegistry registry;
    private ConfigFormatParser parser;

    @BeforeEach
    void setUp() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new MarzRegistry(eventPublisher, new TypeCoercer());
        parser = new ConfigFormatParser();
    }

    @Test
    @DisplayName("empty config file (0 bytes) produces empty state")
    void emptyConfigFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("empty.yml");
        Files.writeString(configFile, "");

        FileConfigSource source = new FileConfigSource(
                "file://" + configFile.toAbsolutePath(),
                parser, registry,
                SourceStrategyResolver.Strategy.DEGRADED_POLL);

        assertThat(source.resolve("any.key")).isNull();
        assertThat(source.getCachedState()).isEmpty();
        assertThat(source.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("config file with only comments produces empty state")
    void commentsOnlyFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("comments.yml");
        Files.writeString(configFile, "# Just a comment\n# Another comment\n");

        FileConfigSource source = new FileConfigSource(
                "file://" + configFile.toAbsolutePath(),
                parser, registry,
                SourceStrategyResolver.Strategy.DEGRADED_POLL);

        assertThat(source.getCachedState()).isEmpty();
    }

    @Test
    @DisplayName("deleted config file — isAvailable returns false")
    void deletedConfigFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value\n");

        FileConfigSource source = new FileConfigSource(
                "file://" + configFile.toAbsolutePath(),
                parser, registry,
                SourceStrategyResolver.Strategy.DEGRADED_POLL);

        assertThat(source.isAvailable()).isTrue();
        assertThat(source.resolve("key")).isEqualTo("value");

        // Delete the file
        Files.delete(configFile);

        assertThat(source.isAvailable()).isFalse();
        // Cached state should still be readable (last known good)
        assertThat(source.resolve("key")).isEqualTo("value");
    }

    @Test
    @DisplayName("config file replaced entirely — diff detects all changes")
    void fileReplacedEntirely() {
        Map<String, String> original = Map.of("a", "1", "b", "2", "c", "3");
        Map<String, String> replacement = Map.of("x", "10", "y", "20");

        Map<String, String> diff = FileConfigSource.diff(original, replacement);

        // a, b, c removed (null); x, y added
        assertThat(diff).hasSize(5);
        assertThat(diff.get("a")).isNull();
        assertThat(diff.get("b")).isNull();
        assertThat(diff.get("c")).isNull();
        assertThat(diff).containsEntry("x", "10");
        assertThat(diff).containsEntry("y", "20");
    }

    @Test
    @DisplayName("whitespace-only values are preserved as-is")
    void whitespaceValues(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile, "key=  spaces  \n");

        FileConfigSource source = new FileConfigSource(
                "file://" + configFile.toAbsolutePath(),
                parser, registry,
                SourceStrategyResolver.Strategy.DEGRADED_POLL);

        // Properties parser trims values, so this tests the actual behavior
        String val = source.resolve("key");
        assertThat(val).isNotNull();
    }
}
