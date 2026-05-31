package com.mymarz.demo;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny helper that prints numbered, visually distinct "STEP N" banners so the
 * demo narrates exactly what is happening at each stage of a run.
 *
 * <p>Every endpoint and benchmark uses its own {@code StepLogger} instance so
 * the step counter is scoped to a single logical flow (one HTTP request, one
 * benchmark). The output is intentionally noisy — this is a teaching demo, the
 * whole point is to <em>see</em> each step.</p>
 */
final class StepLogger {

    private static final String RULE =
            "──────────────────────────────────────────────────────────────";

    private final Logger log;
    private final String flow;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final long startNanos;

    StepLogger(Logger log, String flow) {
        this.log = log;
        this.flow = flow;
        this.startNanos = System.nanoTime();
        log.info("");
        log.info("╔═ START ▶ {} ", flow);
    }

    /** Log a numbered step within this flow. */
    void step(String message, Object... args) {
        int n = counter.incrementAndGet();
        log.info("║ STEP {} ▶ " + message, prepend(n, args));
    }

    /** Log a sub-detail line under the current step (no number). */
    void detail(String message, Object... args) {
        log.info("║        · " + message, args);
    }

    /** Log the closing banner with total elapsed wall-clock time for the flow. */
    void done(String message, Object... args) {
        long elapsedMicros = (System.nanoTime() - startNanos) / 1_000;
        log.info("║ DONE  ▶ " + message, args);
        log.info("╚═ END   ▶ {} ({} steps, {} µs wall-clock)", flow, counter.get(), elapsedMicros);
        log.info(RULE);
    }

    private static Object[] prepend(int n, Object[] args) {
        Object[] out = new Object[args.length + 1];
        out[0] = n;
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }
}
