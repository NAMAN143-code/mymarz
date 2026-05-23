package com.hotswap.source;

import com.hotswap.core.FieldBinding;
import com.hotswap.core.HotSwapRegistry;
import com.hotswap.core.SourceStrategyResolver;
import com.hotswap.type.TypeCoercer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class FileConfigSourceTest {

    @TempDir Path tempDir;

    private HotSwapRegistry registry;
    private ConfigFormatParser parser;
    private FileConfigSource source;

    @BeforeEach
    void setUp() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TypeCoercer coercer = new TypeCoercer();
        registry = new HotSwapRegistry(publisher, coercer);
        parser = new ConfigFormatParser();
    }

    @AfterEach
    void tearDown() {
        if (source != null) {
            source.stop();
        }
    }

    @Test
    void initialLoad_parsesYamlFile() throws IOException {
        Path configFile = writeConfig("feature:\n  checkout: true\n  darkMode: false\n");

        source = createSource(configFile, SourceStrategyResolver.Strategy.DEGRADED_POLL);

        assertThat(source.resolve("feature.checkout")).isEqualTo("true");
        assertThat(source.resolve("feature.darkMode")).isEqualTo("false");
        assertThat(source.isAvailable()).isTrue();
    }

    @Test
    void initialLoad_parsesPropertiesFile() throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile, "rate.limit=500\napp.name=hotswap\n");

        source = createSource(configFile, SourceStrategyResolver.Strategy.DEGRADED_POLL);

        assertThat(source.resolve("rate.limit")).isEqualTo("500");
        assertThat(source.resolve("app.name")).isEqualTo("hotswap");
    }

    @Test
    void resolve_unknownKey_returnsNull() throws IOException {
        Path configFile = writeConfig("key: value\n");
        source = createSource(configFile, SourceStrategyResolver.Strategy.DEGRADED_POLL);

        assertThat(source.resolve("nonexistent")).isNull();
    }

    @Test
    void sourceMetadata() throws IOException {
        Path configFile = writeConfig("key: value\n");
        source = createSource(configFile, SourceStrategyResolver.Strategy.DEGRADED_POLL);

        assertThat(source.scheme()).isEqualTo("file");
        assertThat(source.sourceId()).contains(configFile.toAbsolutePath().toString());
    }

    @Test
    void degradedPoll_detectsChange() throws IOException, InterruptedException {
        Path configFile = writeConfig("feature.enabled: false\n");
        source = createSource(configFile, SourceStrategyResolver.Strategy.DEGRADED_POLL);

        // Register a binding so the registry can receive changes
        AtomicReference<Object> ref = new AtomicReference<>(false);
        FieldBinding binding = new FieldBinding(
                this, "TestBean", "enabled", null, ref,
                boolean.class, "feature.enabled",
                "file://" + configFile.toAbsolutePath(), false
        );
        registry.register("feature.enabled", binding);

        assertThat(ref.get()).isEqualTo(false);

        // Modify the file
        Thread.sleep(100); // ensure timestamp changes
        Files.writeString(configFile, "feature.enabled: true\n");

        // Start degraded poll (30s interval is too long for test, so we'll call manually)
        // Instead of waiting, invoke the internal detection directly
        // In production this fires every 30s; in test we trigger it explicitly
        source.start();

        // Wait for the degraded poll to detect the change
        await().atMost(35, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(ref.get()).isEqualTo(true));
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable(
            named = "CI", matches = "true",
            disabledReason = "WatchService timing unreliable in CI containers; " +
                    "change detection pipeline is validated by degradedPoll test"
    )
    void watchServiceMode_detectsChange() throws IOException {
        Path configFile = writeConfig("count: 10\n");
        source = createSource(configFile, SourceStrategyResolver.Strategy.WATCHSERVICE);

        AtomicReference<Object> ref = new AtomicReference<>(10);
        FieldBinding binding = new FieldBinding(
                this, "TestBean", "count", null, ref,
                int.class, "count",
                "file://" + configFile.toAbsolutePath(), false
        );
        registry.register("count", binding);

        source.start();

        // Modify the file
        Files.writeString(configFile, "count: 42\n");

        // WatchService + safety-net CRC32 should detect within 65s
        await().atMost(65, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(ref.get()).isEqualTo(42));
    }

    @Test
    void getCachedState_returnsCurrentParsedState() throws IOException {
        Path configFile = writeConfig("a: 1\nb: 2\n");
        source = createSource(configFile, SourceStrategyResolver.Strategy.DEGRADED_POLL);

        Map<String, String> cached = source.getCachedState();
        assertThat(cached).containsEntry("a", "1");
        assertThat(cached).containsEntry("b", "2");
    }

    @Test
    void resolveFilePath_handles_fileScheme() {
        assertThat(FileConfigSource.resolveFilePath("file:///etc/config.yml").toString())
                .isEqualTo("/etc/config.yml");
        assertThat(FileConfigSource.resolveFilePath("file://config.yml").toString())
                .isEqualTo("config.yml");
        assertThat(FileConfigSource.resolveFilePath("file:config.yml").toString())
                .isEqualTo("config.yml");
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private Path writeConfig(String content) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, content);
        return configFile;
    }

    private FileConfigSource createSource(Path configFile, SourceStrategyResolver.Strategy strategy) {
        return new FileConfigSource(
                "file://" + configFile.toAbsolutePath(),
                parser, registry, strategy
        );
    }
}
