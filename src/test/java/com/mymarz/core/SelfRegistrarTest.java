package com.mymarz.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SelfRegistrarTest {

    private SelfRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new SelfRegistrar();
    }

    @Test
    @DisplayName("writes missing keys to new YAML file")
    void writesMissingKeysToNewYaml(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        String uri = "file://" + configFile.toAbsolutePath();

        registrar.recordMissing("feature.enabled", "true", uri);
        registrar.recordMissing("rate.limit", "100", uri);
        registrar.writeAll();

        assertThat(configFile).exists();
        String content = Files.readString(configFile);
        assertThat(content).contains("feature");
        assertThat(content).contains("enabled");
        assertThat(content).contains("rate");
        assertThat(content).contains("limit");
    }

    @Test
    @DisplayName("appends missing keys to existing YAML file")
    void appendsToExistingYaml(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "existing:\n  key: value\n");
        String uri = "file://" + configFile.toAbsolutePath();

        registrar.recordMissing("new.key", "default", uri);
        registrar.writeAll();

        String content = Files.readString(configFile);
        assertThat(content).contains("existing");
        assertThat(content).contains("new");
    }

    @Test
    @DisplayName("does not overwrite existing keys in YAML")
    void doesNotOverwriteExistingKeys(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "feature:\n  enabled: false\n");
        String uri = "file://" + configFile.toAbsolutePath();

        registrar.recordMissing("feature.enabled", "true", uri);
        registrar.writeAll();

        String content = Files.readString(configFile);
        assertThat(content).contains("false"); // Original value preserved
    }

    @Test
    @DisplayName("writes missing keys to .properties file")
    void writesToPropertiesFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile, "existing.key=value\n");
        String uri = "file://" + configFile.toAbsolutePath();

        registrar.recordMissing("new.key", "default-value", uri);
        registrar.writeAll();

        String content = Files.readString(configFile);
        assertThat(content).contains("existing.key=value");
        assertThat(content).contains("new.key=default-value");
    }

    @Test
    @DisplayName("creates new .properties file if missing")
    void createsNewPropertiesFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        String uri = "file://" + configFile.toAbsolutePath();

        registrar.recordMissing("app.name", "my-app", uri);
        registrar.writeAll();

        assertThat(configFile).exists();
        String content = Files.readString(configFile);
        assertThat(content).contains("app.name=my-app");
    }

    @Test
    @DisplayName("skips HTTP sources")
    void skipsHttpSources() {
        registrar.recordMissing("key", "value", "http://localhost:8080/config");
        registrar.writeAll();
        // No exception thrown — just silently skipped
    }

    @Test
    @DisplayName("skips platform sources")
    void skipsPlatformSources() {
        registrar.recordMissing("key", "value", "platform://marz");
        registrar.writeAll();
        // No exception thrown — just silently skipped
    }

    @Test
    @DisplayName("no-op when no missing keys recorded")
    void noOpWhenEmpty() {
        registrar.writeAll();
        // No exception thrown
    }

    @Test
    @DisplayName("skips read-only files")
    void skipsReadOnlyFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("readonly.yml");
        Files.writeString(configFile, "key: value\n");
        configFile.toFile().setWritable(false);
        String uri = "file://" + configFile.toAbsolutePath();

        registrar.recordMissing("new.key", "default", uri);
        registrar.writeAll();

        // Should not throw, just log warning
        String content = Files.readString(configFile);
        assertThat(content).doesNotContain("new.key");

        // Cleanup
        configFile.toFile().setWritable(true);
    }

    @Test
    @DisplayName("batches multiple missing keys to same file")
    void batchesMultipleKeys(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        String uri = "file://" + configFile.toAbsolutePath();

        registrar.recordMissing("feature.a", "true", uri);
        registrar.recordMissing("feature.b", "false", uri);
        registrar.recordMissing("rate.limit", "500", uri);
        registrar.writeAll();

        assertThat(configFile).exists();
        String content = Files.readString(configFile);
        assertThat(content).contains("feature");
        assertThat(content).contains("rate");
    }
}
