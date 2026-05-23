package com.hotswap.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SourceStrategyResolverTest {

    private SourceStrategyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SourceStrategyResolver();
    }

    @Test
    void resolveFileStrategy_watchServiceAvailable_returnsWatchService(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value");

        SourceStrategyResolver.Strategy strategy = resolver.resolveFileStrategy(configFile, false);

        // On local filesystem, WatchService should work
        assertThat(strategy).isEqualTo(SourceStrategyResolver.Strategy.WATCHSERVICE);
    }

    @Test
    void resolveFileStrategy_withPlatform_whenWatchServiceWorks_stillUsesWatchService(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value");

        // Even with platform available, WatchService takes priority (Mode A > Mode B)
        SourceStrategyResolver.Strategy strategy = resolver.resolveFileStrategy(configFile, true);

        assertThat(strategy).isEqualTo(SourceStrategyResolver.Strategy.WATCHSERVICE);
    }

    @Test
    void resolveHttpStrategy_alwaysReturnsHttpPoll() {
        assertThat(resolver.resolveHttpStrategy()).isEqualTo(SourceStrategyResolver.Strategy.HTTP_POLL);
    }

    @Test
    void probeWatchService_localFilesystem_returnsTrue(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "key: value");

        assertThat(resolver.probeWatchService(configFile)).isTrue();
    }

    @Test
    void probeWatchService_nonexistentDirectory_returnsFalse() {
        Path fakePath = Path.of("/nonexistent/directory/config.yml");

        assertThat(resolver.probeWatchService(fakePath)).isFalse();
    }

    @Test
    void probeWatchService_nullParent_returnsFalse() {
        Path rootFile = Path.of("config.yml");

        // Path with no parent directory
        assertThat(resolver.probeWatchService(rootFile)).isFalse();
    }
}
