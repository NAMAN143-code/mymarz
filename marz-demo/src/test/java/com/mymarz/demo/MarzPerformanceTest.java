package com.mymarz.demo;

import com.mymarz.core.MarzRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance-test scenario for {@code @Marz}.
 *
 * <p>Boots the full demo Spring context (so every {@code @Marz} field is
 * registered by the library's BeanPostProcessor) and then races the MARZ
 * volatile read path against the naive re-read-from-disk baseline, asserting
 * that MARZ is both <em>faster</em> and does <em>dramatically less IO</em>.</p>
 *
 * <p>Thresholds are deliberately conservative so the test is stable on slow CI
 * boxes — the real-world gap is typically 100×+, we only assert ≥5×.</p>
 */
@SpringBootTest
@DisplayName("@Marz performance scenario")
class MarzPerformanceTest {

    private static final int ITERATIONS = 200_000;

    @Autowired BenchmarkService benchmark;
    @Autowired IoMetrics ioMetrics;
    @Autowired DemoService demoService;
    @Autowired MarzRegistry registry;

    @Test
    @DisplayName("all @Marz fields register at startup")
    void fieldsRegister() {
        assertThat(registry.getRegisteredKeyCount()).isGreaterThanOrEqualTo(6);
        assertThat(registry.getStateSnapshot())
                .containsKey("rate-limit.max-requests-per-minute")
                .containsKey("feature.new-checkout.enabled");
    }

    @Test
    @DisplayName("MARZ volatile read beats naive file re-read on latency and throughput")
    void marzReadIsFaster() {
        BenchmarkService.BenchmarkResult result = benchmark.run(ITERATIONS);

        // Latency: MARZ ns/op must be well below naive ns/op.
        assertThat(result.marz().nanosPerOp())
                .as("MARZ ns/op should be far below naive ns/op")
                .isLessThan(result.naive().nanosPerOp());

        // Throughput: MARZ must sustain more reads/sec.
        assertThat(result.marz().opsPerSecond())
                .isGreaterThan(result.naive().opsPerSecond());

        // Conservative speed-up floor — real gap is usually 100×+.
        assertThat(result.marzSpeedupFactor())
                .as("MARZ should be at least 5x faster than re-reading the file")
                .isGreaterThanOrEqualTo(5.0);
    }

    @Test
    @DisplayName("MARZ read path does ZERO file IO; naive path does one read per access")
    void marzReadPathDoesNoIo() {
        ioMetrics.reset();
        BenchmarkService.BenchmarkResult result = benchmark.run(ITERATIONS);
        IoMetrics.Snapshot io = result.io();

        // Naive path: exactly one file read + parse per measured iteration.
        assertThat(io.naiveFileReads()).isEqualTo(ITERATIONS);
        assertThat(io.naiveParseOps()).isEqualTo(ITERATIONS);
        assertThat(io.naiveBytesRead()).isGreaterThan(0);

        // MARZ read path: no file reads at all (reads only happen on change).
        assertThat(io.marzFileReadsOnChange()).isZero();
        assertThat(io.marzFieldReads()).isEqualTo(ITERATIONS);

        // The whole point: MARZ avoided every byte of read-path IO.
        assertThat(io.ioReductionPercent()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("a runtime hot-swap is visible to readers with no restart")
    void hotSwapIsVisibleWithoutRestart() {
        int before = demoService.getMaxRequestsPerMinute();

        // Simulate the source detecting a change (what the file watcher does).
        registry.onSourceChange(
                "test://perf",
                Map.of("rate-limit.max-requests-per-minute", String.valueOf(before + 900)));

        assertThat(demoService.getMaxRequestsPerMinute())
                .as("reader sees the swapped value immediately, no restart")
                .isEqualTo(before + 900);

        // Restore so test ordering can't leak state.
        registry.onSourceChange(
                "test://perf",
                Map.of("rate-limit.max-requests-per-minute", String.valueOf(before)));
    }
}
