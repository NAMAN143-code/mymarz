package com.mymarz.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expanded {@link SourceStrategyResolver} coverage:
 * <ul>
 *     <li>{@link SourceStrategyResolver.Strategy} enum exhaustively listed</li>
 *     <li>{@code resolveFileStrategy} returns PLATFORM_PRIMARY when probe fails and platform is on</li>
 *     <li>{@code resolveFileStrategy} returns DEGRADED_POLL when probe fails and platform is off</li>
 *     <li>{@code resolveHttpStrategy} is deterministic regardless of inputs</li>
 *     <li>WatchService probe handles relative paths (no parent) gracefully</li>
 * </ul>
 */
class SourceStrategyResolverExpandedTest {

    private SourceStrategyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SourceStrategyResolver();
    }

    // ═══════════════════════════════════════════════════════════════════
    // ENUM SHAPE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Strategy enum has exactly the four documented modes")
    void enumValues() {
        assertThat(SourceStrategyResolver.Strategy.values())
                .containsExactlyInAnyOrder(
                        SourceStrategyResolver.Strategy.WATCHSERVICE,
                        SourceStrategyResolver.Strategy.PLATFORM_PRIMARY,
                        SourceStrategyResolver.Strategy.HTTP_POLL,
                        SourceStrategyResolver.Strategy.DEGRADED_POLL);
    }

    @ParameterizedTest
    @EnumSource(SourceStrategyResolver.Strategy.class)
    @DisplayName("Strategy.valueOf round-trips for every value")
    void enumRoundTrip(SourceStrategyResolver.Strategy s) {
        assertThat(SourceStrategyResolver.Strategy.valueOf(s.name())).isEqualTo(s);
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROBE FAILURE FALLBACKS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("probe failure + platform available → PLATFORM_PRIMARY")
    void probeFailsWithPlatform() {
        // A nonexistent directory makes probeWatchService return false
        Path fakePath = Paths.get("/definitely/does/not/exist/config.yml");

        SourceStrategyResolver.Strategy s = resolver.resolveFileStrategy(fakePath, true);

        assertThat(s).isEqualTo(SourceStrategyResolver.Strategy.PLATFORM_PRIMARY);
    }

    @Test
    @DisplayName("probe failure + platform unavailable → DEGRADED_POLL")
    void probeFailsWithoutPlatform() {
        Path fakePath = Paths.get("/definitely/does/not/exist/config.yml");

        SourceStrategyResolver.Strategy s = resolver.resolveFileStrategy(fakePath, false);

        assertThat(s).isEqualTo(SourceStrategyResolver.Strategy.DEGRADED_POLL);
    }

    @Test
    @DisplayName("probe failure on relative path with no parent → DEGRADED_POLL (no platform)")
    void probeFailsForRelativePath() {
        // A bare filename has a null parent — probe must return false without throwing
        Path rootless = Paths.get("config.yml");

        SourceStrategyResolver.Strategy s = resolver.resolveFileStrategy(rootless, false);

        assertThat(s).isEqualTo(SourceStrategyResolver.Strategy.DEGRADED_POLL);
    }

    @Test
    @DisplayName("probe failure on relative path with no parent + platform → PLATFORM_PRIMARY")
    void probeFailsForRelativePathWithPlatform() {
        Path rootless = Paths.get("config.yml");

        SourceStrategyResolver.Strategy s = resolver.resolveFileStrategy(rootless, true);

        assertThat(s).isEqualTo(SourceStrategyResolver.Strategy.PLATFORM_PRIMARY);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP STRATEGY
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("resolveHttpStrategy is deterministic — always HTTP_POLL")
    void httpStrategyDeterministic() {
        for (int i = 0; i < 5; i++) {
            assertThat(resolver.resolveHttpStrategy())
                    .isEqualTo(SourceStrategyResolver.Strategy.HTTP_POLL);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROBE — DIRECT
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("probeWatchService succeeds on a freshly created temp dir")
    void probeSucceedsInTempDir(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "k: v");

        assertThat(resolver.probeWatchService(file)).isTrue();
    }

    @Test
    @DisplayName("probeWatchService is repeatable on the same directory")
    void probeRepeatable(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "k: v");

        assertThat(resolver.probeWatchService(file)).isTrue();
        assertThat(resolver.probeWatchService(file)).isTrue();
        assertThat(resolver.probeWatchService(file)).isTrue();
    }

    @Test
    @DisplayName("probeWatchService cleans up its temp probe file")
    void probeCleansUpTempFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, "k: v");

        // Run the probe
        resolver.probeWatchService(file);

        // The probe creates `.marz-probe-*.tmp` files and should delete them
        try (var stream = Files.list(tempDir)) {
            long probeFiles = stream
                    .filter(p -> p.getFileName().toString().startsWith(".marz-probe-"))
                    .count();
            assertThat(probeFiles).isZero();
        }
    }
}
