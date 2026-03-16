package com.hotswap.core;

import org.springframework.context.ApplicationEvent;

/**
 * Published via Spring's {@link org.springframework.context.ApplicationEventPublisher}
 * whenever a {@code @HotSwap} field value changes at runtime.
 *
 * <p>Usage:</p>
 * <pre>
 * &#64;EventListener
 * public void onConfigChange(HotSwapEvent event) {
 *     log.info("Key {} changed from {} to {}",
 *         event.getKey(), event.getOldValue(), event.getNewValue());
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public class HotSwapEvent extends ApplicationEvent {

    private final String key;
    private final Object oldValue;
    private final Object newValue;
    private final String configSource;
    private final long eventTimestamp;

    /**
     * Create a new HotSwapEvent.
     *
     * @param bean         the Spring bean containing the changed field
     * @param key          the configuration key that changed
     * @param oldValue     the previous value
     * @param newValue     the new value
     * @param configSource the source URI that provided the change
     */
    public HotSwapEvent(Object bean, String key, Object oldValue, Object newValue, String configSource) {
        super(bean);
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.configSource = configSource;
        this.eventTimestamp = System.currentTimeMillis();
    }

    /** @return the configuration key that changed */
    public String getKey() {
        return key;
    }

    /** @return the previous value before the change */
    public Object getOldValue() {
        return oldValue;
    }

    /** @return the new value after the change */
    public Object getNewValue() {
        return newValue;
    }

    /** @return the source URI that provided this change */
    public String getConfigSource() {
        return configSource;
    }

    @Override
    public long getTimestamp() {
        return eventTimestamp;
    }

    @Override
    public String toString() {
        return "HotSwapEvent{key='" + key + "', oldValue=" + oldValue
                + ", newValue=" + newValue + ", source='" + configSource + "'}";
    }
}
