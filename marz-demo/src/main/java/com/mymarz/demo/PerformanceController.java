package com.mymarz.demo;

import com.mymarz.core.MarzRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoints that expose the performance and IO metrics of the
 * {@code @Marz} read path versus the naive re-read-from-disk baseline.
 *
 * <pre>
 *   GET /perf?iterations=1000000   → run the benchmark, return latency + throughput + IO
 *   GET /metrics/io                → current IO counters (file reads, bytes, parses)
 *   GET /metrics/perf              → registry stats (registered keys / bindings)
 * </pre>
 */
@RestController
public class PerformanceController {

    private static final Logger log = LoggerFactory.getLogger(PerformanceController.class);

    private final BenchmarkService benchmark;
    private final IoMetrics ioMetrics;
    private final MarzRegistry registry;

    public PerformanceController(BenchmarkService benchmark, IoMetrics ioMetrics, MarzRegistry registry) {
        this.benchmark = benchmark;
        this.ioMetrics = ioMetrics;
        this.registry = registry;
    }

    /**
     * Run the head-to-head performance benchmark.
     *
     * @param iterations measured reads per strategy (default 1,000,000; capped at 50M)
     */
    @GetMapping("/perf")
    public BenchmarkService.BenchmarkResult perf(
            @RequestParam(defaultValue = "1000000") int iterations) {
        int safe = Math.max(1, Math.min(iterations, 50_000_000));
        log.info("HTTP GET /perf?iterations={} → running benchmark", safe);
        return benchmark.run(safe);
    }

    /** Current IO counters — see how much file IO each strategy has done so far. */
    @GetMapping("/metrics/io")
    public IoMetrics.Snapshot io() {
        log.info("HTTP GET /metrics/io → returning IO counters");
        return ioMetrics.snapshot();
    }

    /** MARZ registry / runtime stats — how many keys and field bindings are live. */
    @GetMapping("/metrics/perf")
    public Map<String, Object> perfStats() {
        log.info("HTTP GET /metrics/perf → returning registry stats");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registeredKeys", registry.getRegisteredKeyCount());
        out.put("totalFieldBindings", registry.getTotalBindingCount());
        out.put("readPath", "volatile field read (~5ns, zero IO, zero reflection)");
        out.put("writePath", "background thread, reflection swap, only on config change");
        out.put("currentState", registry.getStateSnapshot());
        return out;
    }
}
