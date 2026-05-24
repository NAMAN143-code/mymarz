package com.mymarz.core;

import org.springframework.context.ApplicationEvent;

/**
 * Published via Spring's {@link org.springframework.context.ApplicationEventPublisher}
 * whenever a {@code @Marz} field value changes at runtime.
 *
 * <p>When the field is marked {@code @Marz(sensitive = true)}, both
 * {@code oldValue} and {@code newValue} are masked as {@code ***} in
 * {@link #toString()} to prevent secrets from leaking into logs.</p>
 *
 * @since 1.0.0
 */
public class MarzEvent extends ApplicationEvent {

    private static final String MASKED = "***";

    private final String key;
    private final Object oldValue;
    private final Object newValue;
    private final String configSource;
    private final boolean sensitive;

    /**
     * Create a new MarzEvent.
     *
     * @param bean         the Spring bean containing the changed field
     * @param key          the configuration key that changed
     * @param oldValue     the previous value
     * @param newValue     the new value
     * @param configSource the source URI that provided the change
     * @param sensitive    whether values should be masked in toString()
     */
    public MarzEvent(Object bean, String key, Object oldValue, Object newValue,
                        String configSource, boolean sensitive) {
        super(bean);
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.configSource = configSource;
        this.sensitive = sensitive;
    }

    public String getKey() { return key; }
    public Object getOldValue() { return oldValue; }
    public Object getNewValue() { return newValue; }
    public String getConfigSource() { return configSource; }
    public boolean isSensitive() { return sensitive; }

    @Override
    public String toString() {
        if (sensitive) {
            return "MarzEvent{key='" + key + "', oldValue=" + MASKED
                    + ", newValue=" + MASKED + ", source='" + configSource + "'}";
        }
        return "MarzEvent{key='" + key + "', oldValue=" + oldValue
                + ", newValue=" + newValue + ", source='" + configSource + "'}";
    }
}
