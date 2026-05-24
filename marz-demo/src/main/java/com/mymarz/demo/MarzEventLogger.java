package com.mymarz.demo;

import com.mymarz.core.MarzEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for @Marz value changes and logs them.
 *
 * Watch the console output while editing config/marz-demo.yml
 * to see changes detected in real-time.
 */
@Component
public class MarzEventLogger {

    private static final Logger log = LoggerFactory.getLogger(MarzEventLogger.class);

    @EventListener
    public void onConfigChange(MarzEvent event) {
        log.info("CONFIG CHANGED: [{}] {} -> {} (source: {})",
                event.getKey(),
                event.getOldValue(),
                event.getNewValue(),
                event.getConfigSource());
    }
}
