package com.mymarz.demo;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters for the two config-access strategies the demo compares.
 *
 * <p>The whole pitch of {@code @Marz} is that the <strong>read path does zero
 * IO</strong>: application code reads a {@code volatile} field straight out of
 * memory. The "naive" baseline ({@link NaiveConfigService}) instead re-opens and
 * re-parses the YAML file on every single access. These counters make that
 * difference concrete and reportable via {@code /metrics/io}.</p>
 *
 * <p>All counters are {@link AtomicLong} so they are safe to bump from the many
 * concurrent threads a benchmark or load test will use.</p>
 */
@Component
public class IoMetrics {

    // ─── Naive baseline: re-reads the file on every access ───────────────
    private final AtomicLong naiveFileReads = new AtomicLong();
    private final AtomicLong naiveBytesRead = new AtomicLong();
    private final AtomicLong naiveParseOps  = new AtomicLong();
    private final AtomicLong naiveReadNanos = new AtomicLong();

    // ─── MARZ path: volatile in-memory reads, zero IO ────────────────────
    private final AtomicLong marzFieldReads = new AtomicLong();
    private final AtomicLong marzReadNanos  = new AtomicLong();

    // ─── MARZ background swap activity (write path, off the hot read path) ─
    private final AtomicLong marzFileReadsOnChange = new AtomicLong();
    private final AtomicLong marzSwaps             = new AtomicLong();

    // ── recording (naive) ────────────────────────────────────────────────

    /** Record one file open+read on the naive path. */
    public void recordNaiveFileRead(long bytes, long elapsedNanos) {
        naiveFileReads.incrementAndGet();
        naiveBytesRead.addAndGet(bytes);
        naiveParseOps.incrementAndGet();
        naiveReadNanos.addAndGet(elapsedNanos);
    }

    // ── recording (marz) ─────────────────────────────────────────────────

    /** Record one in-memory volatile read on the MARZ path. */
    public void recordMarzRead(long elapsedNanos) {
        marzFieldReads.incrementAndGet();
        marzReadNanos.addAndGet(elapsedNanos);
    }

    /** Record a MARZ background swap (write path) — one file read + N field swaps. */
    public void recordMarzSwap() {
        marzFileReadsOnChange.incrementAndGet();
        marzSwaps.incrementAndGet();
    }

    // ── snapshot ──────────────────────────────────────────────────────────

    /** Immutable point-in-time view of all counters, plus derived ratios. */
    public Snapshot snapshot() {
        long nf = naiveFileReads.get();
        long mf = marzFieldReads.get();
        long ioSaved = nf; // every naive read was a file read; MARZ avoided all of them on the read path
        double ioReductionPct = nf == 0 ? 0.0 : 100.0 * (nf - marzFileReadsOnChange.get()) / nf;
        return new Snapshot(
                nf, naiveBytesRead.get(), naiveParseOps.get(), naiveReadNanos.get(),
                mf, marzReadNanos.get(),
                marzFileReadsOnChange.get(), marzSwaps.get(),
                ioSaved, round2(ioReductionPct));
    }

    /** Reset every counter (used between benchmark runs). */
    public void reset() {
        naiveFileReads.set(0);
        naiveBytesRead.set(0);
        naiveParseOps.set(0);
        naiveReadNanos.set(0);
        marzFieldReads.set(0);
        marzReadNanos.set(0);
        marzFileReadsOnChange.set(0);
        marzSwaps.set(0);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Snapshot of IO counters. Field names map directly to the JSON returned by
     * {@code GET /metrics/io}.
     */
    public record Snapshot(
            long naiveFileReads,
            long naiveBytesRead,
            long naiveParseOps,
            long naiveReadNanos,
            long marzFieldReads,
            long marzReadNanos,
            long marzFileReadsOnChange,
            long marzSwaps,
            long ioOperationsSaved,
            double ioReductionPercent
    ) {}
}
