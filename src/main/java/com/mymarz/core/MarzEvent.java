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
 * <p><strong>Failure events</strong> (KAN-98): when a change cannot be applied —
 * e.g. an un-coercible value such as {@code "abc"} for an {@code int} key — a
 * failure event is published instead of a change event. Failure events carry the
 * offending raw value as {@link #getNewValue()} and the cause via
 * {@link #getFailure()}, and report {@code true} from {@link #isFailure()}. This
 * is the seam the observability module (ADR-008) consumes for a swap-failure
 * metric; ordinary change listeners can ignore them via {@code !event.isFailure()}.</p>
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
    private final Throwable failure;

    /**
     * Create a new MarzEvent for a successfully applied change.
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
        this(bean, key, oldValue, newValue, configSource, sensitive, null);
    }

    /**
     * Full constructor. When {@code failure} is non-null the event represents a
     * change that could not be applied (see {@link #isFailure()}).
     *
     * @param failure the cause of an apply failure, or {@code null} for a normal change
     */
    public MarzEvent(Object bean, String key, Object oldValue, Object newValue,
                        String configSource, boolean sensitive, Throwable failure) {
        super(bean);
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.configSource = configSource;
        this.sensitive = sensitive;
        this.failure = failure;
    }

    /**
     * Build a failure event for a value that could not be coerced or written.
     *
     * @param bean              the bean whose field could not be updated
     * @param key               the configuration key
     * @param attemptedRawValue the raw source value that failed to apply (may be {@code null} for a removed key)
     * @param configSource      the source URI that provided the change
     * @param sensitive         whether the value should be masked in logs
     * @param cause             the coercion/write failure
     * @return a failure {@code MarzEvent}
     */
    public static MarzEvent failure(Object bean, String key, String attemptedRawValue,
                                    String configSource, boolean sensitive, Throwable cause) {
        return new MarzEvent(bean, key, null, attemptedRawValue, configSource, sensitive,
                cause != null ? cause : new IllegalStateException("unknown apply failure"));
    }

    public String getKey() { return key; }
    public Object getOldValue() { return oldValue; }
    public Object getNewValue() { return newValue; }
    public String getConfigSource() { return configSource; }
    public boolean isSensitive() { return sensitive; }

    /** @return {@code true} if this event represents a change that failed to apply. */
    public boolean isFailure() { return failure != null; }

    /** @return the apply-failure cause, or {@code null} for a normal change event. */
    public Throwable getFailure() { return failure; }

    @Override
    public String toString() {
        if (failure != null) {
            String attempted = sensitive ? MASKED : String.valueOf(newValue);
            return "MarzEvent{FAILED key='" + key + "', attemptedValue=" + attempted
                    + ", source='" + configSource + "', error=" + failure.getMessage() + "}";
        }
        if (sensitive) {
            return "MarzEvent{key='" + key + "', oldValue=" + MASKED
                    + ", newValue=" + MASKED + ", source='" + configSource + "'}";
        }
        return "MarzEvent{key='" + key + "', oldValue=" + oldValue
                + ", newValue=" + newValue + ", source='" + configSource + "'}";
    }
}
