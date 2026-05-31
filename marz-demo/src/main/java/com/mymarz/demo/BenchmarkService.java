package com.mymarz.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Head-to-head performance harness that races the two config-read strategies:
 *
 * <ol>
 *   <li><b>MARZ</b> — read a {@code volatile} field via {@link DemoService}
 *       (in-memory, ~nanoseconds, zero IO).</li>
 *   <li><b>Naive</b> — re-read + re-parse the YAML file via
 *       {@link NaiveConfigService} (syscall + IO + parse, ~microseconds).</li>
 * </ol>
 *
 * <p>Both strategies return the <em>same logical value</em>, so this is a fair
 * apples-to-apples comparison of the cost of "always-fresh config". The harness
 * does a warmup pass (let the JIT compile the hot loop) then a measured pass,
 * and reports latency, throughput, and the IO each path incurred.</p>
 *
 * <p>Every stage is narrated through {@link StepLogger} so you can watch the
 * benchmark progress in the console.</p>
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    private final DemoService marz;
    private final NaiveConfigService naive;
    private final IoMetrics ioMetrics;

    public BenchmarkService(DemoService marz, NaiveConfigService naive, IoMetrics ioMetrics) {
        this.marz = marz;
        this.naive = naive;
        this.ioMetrics = ioMetrics;
    }

    /**
     * Run the benchmark.
     *
     * @param iterations number of measured reads per strategy
     * @return the head-to-head result
     */
    public BenchmarkResult run(int iterations) {
        int warmup = Math.max(1_000, iterations / 10);
        StepLogger steps = new StepLogger(log, "PERFORMANCE BENCHMARK (" + iterations + " iterations)");

        steps.step("Reset IO counters so this run is measured in isolation");
        ioMetrics.reset();

        steps.step("Warm up the JIT — {} reads per strategy (not measured)", warmup);
        long warmBlackhole = 0;
        for (int i = 0; i < warmup; i++) {
            warmBlackhole += marz.getMaxRequestsPerMinute();
            warmBlackhole += naive.getMaxRequestsPerMinute();
        }
        steps.detail("warmup blackhole={} (kept so the JIT can't dead-code the loop)", warmBlackhole);

        steps.step("Reset IO counters again — warmup IO does not count toward the result");
        ioMetrics.reset();

        steps.step("Measure MARZ path — {} × volatile field reads (zero IO)", iterations);
        long marzBlackhole = 0;
        long marzStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            marzBlackhole += marz.getMaxRequestsPerMinute();
            ioMetrics.recordMarzRead(System.nanoTime() - t0);
        }
        long marzTotalNanos = System.nanoTime() - marzStart;
        steps.detail("MARZ done: {} ns total, blackhole={}", marzTotalNanos, marzBlackhole);

        steps.step("Measure NAIVE path — {} × file re-read + YAML re-parse (IO on every read)", iterations);
        long naiveBlackhole = 0;
        long naiveStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            naiveBlackhole += naive.getMaxRequestsPerMinute();
        }
        long naiveTotalNanos = System.nanoTime() - naiveStart;
        steps.detail("NAIVE done: {} ns total, blackhole={}", naiveTotalNanos, naiveBlackhole);

        steps.step("Compute latency, throughput, and the speed-up factor");
        StrategyResult marzResult  = StrategyResult.of("marz",  iterations, marzTotalNanos);
        StrategyResult naiveResult = StrategyResult.of("naive", iterations, naiveTotalNanos);
        double speedup = marzResult.nanosPerOp() == 0
                ? Double.POSITIVE_INFINITY
                : round2((double) naiveResult.nanosPerOp() / marzResult.nanosPerOp());

        IoMetrics.Snapshot io = ioMetrics.snapshot();
        steps.detail("MARZ  : {} ns/op, {} ops/sec", marzResult.nanosPerOp(), marzResult.opsPerSecond());
        steps.detail("NAIVE : {} ns/op, {} ops/sec", naiveResult.nanosPerOp(), naiveResult.opsPerSecond());
        steps.detail("speed-up: MARZ is {}× faster; IO ops avoided on read path: {}",
                speedup, io.naiveFileReads());

        steps.done("MARZ {}× faster, {} bytes of file IO avoided", speedup, io.naiveBytesRead());

        return new BenchmarkResult(iterations, warmup, marzResult, naiveResult, speedup, io);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Per-strategy latency/throughput numbers. */
    public record StrategyResult(
            String strategy,
            long iterations,
            long totalNanos,
            long nanosPerOp,
            long opsPerSecond
    ) {
        static StrategyResult of(String strategy, long iterations, long totalNanos) {
            long nsPerOp = iterations == 0 ? 0 : totalNanos / iterations;
            long opsPerSec = nsPerOp == 0 ? 0 : 1_000_000_000L / nsPerOp;
            return new StrategyResult(strategy, iterations, totalNanos, nsPerOp, opsPerSec);
        }
    }

    /** Full benchmark output — serialized directly to JSON by {@code GET /perf}. */
    public record BenchmarkResult(
            int iterations,
            int warmupIterations,
            StrategyResult marz,
            StrategyResult naive,
            double marzSpeedupFactor,
            IoMetrics.Snapshot io
    ) {}
}
