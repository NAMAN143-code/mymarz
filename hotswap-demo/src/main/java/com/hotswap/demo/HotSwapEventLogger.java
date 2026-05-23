package com.hotswap.demo;

import com.hotswap.core.HotSwapEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for @HotSwap value changes and logs them.
 *
 * Watch the console output while editing config/hotswap-demo.yml
 * to see changes detected in real-time.
 */
@Component
public class HotSwapEventLogger {

    private static final Logger log = LoggerFactory.getLogger(HotSwapEventLogger.class);

    @EventListener
    public void onConfigChange(HotSwapEvent event) {
        log.info("CONFIG CHANGED: [{}] {} -> {} (source: {})",
                event.getKey(),
                event.getOldValue(),
                event.getNewValue(),
                event.getConfigSource());
    }
}
