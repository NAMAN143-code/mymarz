package com.hotswap.core;

import org.springframework.context.ApplicationEvent;

/**
 * Published via Spring's {@link org.springframework.context.ApplicationEventPublisher}
 * whenever a {@code @HotSwap} field value changes at runtime.
 *
 * <p>When {@code sensitive} is {@code true}, {@link #toString()} masks
 * values as {@code ***} to prevent secrets leaking into logs, monitoring
 * dashboards, or third-party event listeners.</p>
 *
 * @since 1.0.0
 */
public class HotSwapEvent extends ApplicationEvent {

    private final String key;
    private final Object oldValue;
    private final Object newValue;
    private final String configSource;
    private final boolean sensitive;

    /**
     * Create a new HotSwapEvent.
     *
     * @param bean         the Spring bean containing the changed field
     * @param key          the configuration key that changed
     * @param oldValue     the previous value
     * @param newValue     the new value
     * @param configSource the source URI that provided the change
     * @param sensitive    whether values should be masked in toString()
     */
    public HotSwapEvent(Object bean, String key, Object oldValue, Object newValue,
                        String configSource, boolean sensitive) {
        super(bean);
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.configSource = configSource;
        this.sensitive = sensitive;
    }

    /** @return the configuration key that changed */
    public String getKey() { return key; }

    /** @return the previous value before the change */
    public Object getOldValue() { return oldValue; }

    /** @return the new value after the change */
    public Object getNewValue() { return newValue; }

    /** @return the source URI that provided this change */
    public String getConfigSource() { return configSource; }

    /** @return whether this event carries sensitive values */
    public boolean isSensitive() { return sensitive; }

    /**
     * Returns a string representation. Sensitive values are masked as {@code ***}
     * to prevent secrets leaking into logs or monitoring systems.
     */
    @Override
    public String toString() {
        if (sensitive) {
            return "HotSwapEvent{key='" + key + "', oldValue=***, newValue=***, source='" + configSource + "'}";
        }
        return "HotSwapEvent{key='" + key + "', oldValue=" + oldValue
                + ", newValue=" + newValue + ", source='" + configSource + "'}";
    }
}
