package com.mymarz.demo;

import com.mymarz.core.MarzRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Narrates what MARZ did during startup and runs a small self-benchmark so the
 * very first thing you see in the logs is proof that the read path is fast and
 * IO-free.
 *
 * <p>Runs once, after the Spring context is fully initialized and every
 * {@code @Marz} field has been registered by the library's BeanPostProcessor.</p>
 */
@Component
public class StartupNarrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupNarrator.class);

    private final MarzRegistry registry;
    private final BenchmarkService benchmark;

    public StartupNarrator(MarzRegistry registry, BenchmarkService benchmark) {
        this.registry = registry;
        this.benchmark = benchmark;
    }

    @Override
    public void run(ApplicationArguments args) {
        StepLogger steps = new StepLogger(log, "MARZ DEMO STARTUP");

        steps.step("MARZ registered {} config key(s) across {} field binding(s)",
                registry.getRegisteredKeyCount(), registry.getTotalBindingCount());
        registry.getStateSnapshot().forEach((key, snap) ->
                steps.detail("key='{}' type={} value={}", key, snap.type(),
                        snap.sensitive() ? "***" : snap.value()));

        steps.step("Running a quick self-benchmark (100k reads/strategy) to prove the read path...");
        BenchmarkService.BenchmarkResult result = benchmark.run(100_000);

        steps.step("Result: MARZ volatile read = {} ns/op; naive file re-read = {} ns/op",
                result.marz().nanosPerOp(), result.naive().nanosPerOp());
        steps.detail("MARZ is {}× faster and avoided {} file reads / {} bytes of IO",
                result.marzSpeedupFactor(), result.io().naiveFileReads(), result.io().naiveBytesRead());

        steps.step("Try it live:");
        steps.detail("curl localhost:8080/config              # current @Marz values");
        steps.detail("curl 'localhost:8080/perf?iterations=1000000'  # run the benchmark");
        steps.detail("curl localhost:8080/metrics/io          # IO counters");
        steps.detail("curl localhost:8080/metrics/perf        # registry stats");
        steps.detail("then edit config/marz-demo.yml and watch the CONFIG CHANGE logs");

        steps.done("Demo ready on http://localhost:8080");
    }
}
