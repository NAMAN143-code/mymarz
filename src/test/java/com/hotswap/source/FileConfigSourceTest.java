package com.hotswap.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileConfigSource}.
 */
class FileConfigSourceTest {

    @TempDir
    Path tempDir;

    private ConfigFormatParser parser;

    @BeforeEach
    void setUp() {
        parser = new ConfigFormatParser();
    }

    // -------------------------------------------------------------------------
    // resolve()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("resolve()")
    class Resolve {

        @Test
        @DisplayName("resolves key from YAML file")
        void resolvesYamlKey() throws IOException {
            Path file = tempDir.resolve("config.yml");
            Files.writeString(file, "feature:\n  checkout:\n    v2: true\n");

            FileConfigSource source = new FileConfigSource("file://" + file, parser);
            assertThat(source.resolve("feature.checkout.v2")).isEqualTo("true");
        }

        @Test
        @DisplayName("resolves key from JSON file")
        void resolvesJsonKey() throws IOException {
            Path file = tempDir.resolve("config.json");
            Files.writeString(file, "{\"timeout\": \"30\", \"feature.enabled\": \"true\"}");

            FileConfigSource source = new FileConfigSource("file://" + file, parser);
            assertThat(source.resolve("timeout")).isEqualTo("30");
        }

        @Test
        @DisplayName("resolves key from .properties file")
        void resolvesPropertiesKey() throws IOException {
            Path file = tempDir.resolve("config.properties");
            Files.writeString(file, "feature.enabled=true\ntimeout=30\n");

            FileConfigSource source = new FileConfigSource("file://" + file, parser);
            assertThat(source.resolve("feature.enabled")).isEqualTo("true");
            assertThat(source.resolve("timeout")).isEqualTo("30");
        }

        @Test
        @DisplayName("returns null for missing key")
        void returnsNullForMissingKey() throws IOException {
            Path file = tempDir.resolve("config.yml");
            Files.writeString(file, "key: value\n");

            FileConfigSource source = new FileConfigSource("file://" + file, parser);
            assertThat(source.resolve("nonexistent.key")).isNull();
        }

        @Test
        @DisplayName("returns null gracefully when file does not exist")
        void returnsNullWhenFileMissing() {
            FileConfigSource source = new FileConfigSource(
                    "file:///nonexistent/path/config.yml", parser);
            assertThat(source.resolve("any.key")).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Cache invalidation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Cache invalidation")
    class CacheInvalidation {

        @Test
        @DisplayName("picks up updated value after file modification")
        void detectsFileChange() throws IOException, InterruptedException {
            Path file = tempDir.resolve("config.yml");
            Files.writeString(file, "feature.enabled: false\n");

            FileConfigSource source = new FileConfigSource("file://" + file, parser);
            assertThat(source.resolve("feature.enabled")).isEqualTo("false");

            // Ensure filesystem timestamp advances (some systems have 1s resolution)
            Thread.sleep(10);
            Files.writeString(file, "feature.enabled: true\n");
            // Touch the file to guarantee mtime change on fast filesystems
            file.toFile().setLastModified(System.currentTimeMillis() + 1000);

            assertThat(source.resolve("feature.enabled")).isEqualTo("true");
        }

        @Test
        @DisplayName("does not re-read file when timestamp is unchanged")
        void doesNotReReadWhenUnchanged() throws IOException {
            Path file = tempDir.resolve("config.yml");
            Files.writeString(file, "key: original\n");

            FileConfigSource source = new FileConfigSource("file://" + file, parser);
            source.resolve("key"); // prime the cache

            long firstLastModified = source.getLastModified();

            // Resolve again without touching the file
            source.resolve("key");

            assertThat(source.getLastModified()).isEqualTo(firstLastModified);
        }
    }

    // -------------------------------------------------------------------------
    // isAvailable()
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailable {

        @Test
        @DisplayName("returns true when file exists and is readable")
        void trueWhenFileExists() throws IOException {
            Path file = tempDir.resolve("config.yml");
            Files.writeString(file, "key: value\n");
            FileConfigSource source = new FileConfigSource("file://" + file, parser);
            assertThat(source.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("returns false when file does not exist")
        void falseWhenFileMissing() {
            FileConfigSource source = new FileConfigSource(
                    "file:///nonexistent/config.yml", parser);
            assertThat(source.isAvailable()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // scheme() and sourceId()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("scheme() returns 'file'")
    void schemeIsFile() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "");
        FileConfigSource source = new FileConfigSource("file://" + file, parser);
        assertThat(source.scheme()).isEqualTo("file");
    }

    @Test
    @DisplayName("sourceId() returns absolute path prefixed with 'file:'")
    void sourceIdIsAbsolutePath() throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "");
        FileConfigSource source = new FileConfigSource("file://" + file, parser);
        assertThat(source.sourceId()).startsWith("file:").contains(file.getFileName().toString());
    }

    // -------------------------------------------------------------------------
    // resolveFilePath() URI parsing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("resolveFilePath() URI parsing")
    class ResolveFilePath {

        @Test
        @DisplayName("handles file:///absolute/path format")
        void tripleSlashFormat() {
            Path result = FileConfigSource.resolveFilePath("file:///tmp/config.yml");
            assertThat(result.toString()).isEqualTo("/tmp/config.yml");
        }

        @Test
        @DisplayName("handles file://relative/path format")
        void doubleSlashFormat() {
            Path result = FileConfigSource.resolveFilePath("file://relative/config.yml");
            assertThat(result.toString()).isEqualTo("relative/config.yml");
        }

        @Test
        @DisplayName("handles file:path format")
        void singleColonFormat() {
            Path result = FileConfigSource.resolveFilePath("file:config.yml");
            assertThat(result.toString()).isEqualTo("config.yml");
        }

        @Test
        @DisplayName("handles bare path with no scheme")
        void barePathFormat() {
            Path result = FileConfigSource.resolveFilePath("/tmp/config.yml");
            assertThat(result.toString()).isEqualTo("/tmp/config.yml");
        }
    }
}
