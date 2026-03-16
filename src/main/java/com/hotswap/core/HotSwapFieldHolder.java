package com.hotswap.core;

import com.hotswap.annotation.HotSwap;
import com.hotswap.annotation.HotSwapType;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds metadata and state for a single {@code @HotSwap}-annotated field.
 *
 * <p>Each registered field gets one holder. The holder stores the
 * {@link AtomicReference} that backs thread-safe reads/writes,
 * plus the annotation metadata needed by the polling engine.</p>
 *
 * @since 1.0.0
 */
public class HotSwapFieldHolder {

    private final Object bean;
    private final Field field;
    private final String key;
    private final String source;
    private final long pollInterval;
    private final String defaultValue;
    private final HotSwapType type;
    private final String description;
    private final boolean requiresApproval;
    private final AtomicReference<Object> valueRef;

    public HotSwapFieldHolder(Object bean, Field field, HotSwap annotation, Object initialValue) {
        this.bean = bean;
        this.field = field;
        this.key = annotation.key();
        this.source = annotation.source();
        this.pollInterval = annotation.pollInterval();
        this.defaultValue = annotation.defaultValue();
        this.type = annotation.type();
        this.description = annotation.description();
        this.requiresApproval = annotation.requiresApproval();
        this.valueRef = new AtomicReference<>(initialValue);
    }

    /** @return the Spring bean containing this field */
    public Object getBean() {
        return bean;
    }

    /** @return the annotated field */
    public Field getField() {
        return field;
    }

    /** @return the config key to resolve */
    public String getKey() {
        return key;
    }

    /** @return the source URI */
    public String getSource() {
        return source;
    }

    /** @return the poll interval in ms */
    public long getPollInterval() {
        return pollInterval;
    }

    /** @return the default value string */
    public String getDefaultValue() {
        return defaultValue;
    }

    /** @return the explicit type hint */
    public HotSwapType getType() {
        return type;
    }

    /** @return the description for platform UI */
    public String getDescription() {
        return description;
    }

    /** @return whether changes require RBAC approval */
    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    /** @return the thread-safe value reference */
    public AtomicReference<Object> getValueRef() {
        return valueRef;
    }

    /** Get current value. */
    public Object getCurrentValue() {
        return valueRef.get();
    }

    /**
     * Update the value atomically and write it back to the bean field.
     *
     * @param newValue the new value
     * @return the old value
     */
    public Object updateValue(Object newValue) {
        Object oldValue = valueRef.getAndSet(newValue);
        writeToField(newValue);
        return oldValue;
    }

    /**
     * Write a value directly to the bean's field via reflection.
     */
    private void writeToField(Object value) {
        try {
            field.setAccessible(true);
            field.set(bean, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(
                    "Failed to write value to field " + field.getDeclaringClass().getSimpleName()
                            + "." + field.getName(), e);
        }
    }

    @Override
    public String toString() {
        return "HotSwapFieldHolder{key='" + key + "', source='" + source
                + "', bean=" + bean.getClass().getSimpleName()
                + ", field=" + field.getName() + "}";
    }
}
