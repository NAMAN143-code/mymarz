package com.mymarz.demo;

import com.mymarz.core.MarzRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Publishes the demo's IO and MARZ runtime counters as Micrometer gauges so
 * they show up under {@code GET /actuator/metrics} alongside JVM metrics — the
 * standard place ops teams look.
 *
 * <p>Gauge names are namespaced under {@code marz.*}; browse them with:</p>
 * <pre>
 *   curl localhost:8080/actuator/metrics/marz.io.naive.file-reads
 *   curl localhost:8080/actuator/metrics/marz.read.field-reads
 *   curl localhost:8080/actuator/metrics/marz.registry.keys
 * </pre>
 */
@Component
public class MarzMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(MarzMetricsBinder.class);

    private final MeterRegistry meterRegistry;
    private final IoMetrics ioMetrics;
    private final MarzRegistry registry;

    public MarzMetricsBinder(MeterRegistry meterRegistry, IoMetrics ioMetrics, MarzRegistry registry) {
        this.meterRegistry = meterRegistry;
        this.ioMetrics = ioMetrics;
        this.registry = registry;
    }

    @PostConstruct
    void bind() {
        meterRegistry.gauge("marz.io.naive.file-reads", ioMetrics, m -> m.snapshot().naiveFileReads());
        meterRegistry.gauge("marz.io.naive.bytes-read", ioMetrics, m -> m.snapshot().naiveBytesRead());
        meterRegistry.gauge("marz.io.reduction-percent", ioMetrics, m -> m.snapshot().ioReductionPercent());
        meterRegistry.gauge("marz.read.field-reads", ioMetrics, m -> m.snapshot().marzFieldReads());
        meterRegistry.gauge("marz.write.swaps", ioMetrics, m -> m.snapshot().marzSwaps());
        meterRegistry.gauge("marz.registry.keys", registry, MarzRegistry::getRegisteredKeyCount);
        meterRegistry.gauge("marz.registry.bindings", registry, MarzRegistry::getTotalBindingCount);
        log.info("Bound MARZ IO/runtime gauges to Micrometer (see /actuator/metrics/marz.*)");
    }
}
