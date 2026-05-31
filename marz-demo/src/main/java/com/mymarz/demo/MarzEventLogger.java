package com.mymarz.demo;

import com.mymarz.core.MarzEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for @Marz value changes and logs them, narrating the write path
 * step-by-step.
 *
 * <p>A {@link MarzEvent} is the tail end of MARZ's <em>write path</em>: the
 * background watcher read the file once, diffed it, and swapped the volatile
 * field. We record that as one swap in {@link IoMetrics} so {@code /metrics/io}
 * shows that file IO happens only on change — never on the read path.</p>
 *
 * Watch the console output while editing config/marz-demo.yml to see changes
 * detected in real-time.
 */
@Component
public class MarzEventLogger {

    private static final Logger log = LoggerFactory.getLogger(MarzEventLogger.class);

    private final IoMetrics ioMetrics;

    public MarzEventLogger(IoMetrics ioMetrics) {
        this.ioMetrics = ioMetrics;
    }

    @EventListener
    public void onConfigChange(MarzEvent event) {
        StepLogger steps = new StepLogger(log, "CONFIG CHANGE [" + event.getKey() + "]");
        steps.step("Background watcher detected a change in source: {}", event.getConfigSource());
        steps.step("Volatile field swapped: [{}] {} -> {}",
                event.getKey(),
                event.isSensitive() ? "***" : event.getOldValue(),
                event.isSensitive() ? "***" : event.getNewValue());
        steps.step("All application threads now see the new value on their next read (~5ns)");
        ioMetrics.recordMarzSwap();
        steps.done("Change applied with NO restart, NO redeploy");
    }
}
