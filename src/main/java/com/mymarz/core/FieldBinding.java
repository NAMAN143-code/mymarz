package com.mymarz.core;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable binding between a config key and a volatile field's in-memory location.
 *
 * <p>Created once during {@link MarzBeanPostProcessor} scanning.
 * Never modified after creation.</p>
 *
 * <p><strong>Read path:</strong> Application code reads the {@code volatile} field
 * directly (~5ns volatile read, zero IO, zero reflection). This is the hot path.</p>
 *
 * <p><strong>Write path:</strong> {@link MarzRegistry#onSourceChange} writes the
 * new value to the volatile field via {@code field.set(bean, newValue)}. Because the
 * field is volatile, the JMM guarantees the write is visible to all threads immediately.
 * The {@code ref} (AtomicReference) is updated in parallel for CAS-based event
 * deduplication and health/metrics snapshots.</p>
 *
 * <p>Part of the reverse index in {@link MarzRegistry}:
 * config key → List&lt;FieldBinding&gt;</p>
 *
 * @param bean          the Spring bean instance containing the field
 * @param beanClassName e.g., "com.acme.PaymentService" (for logging)
 * @param fieldName     e.g., "newCheckoutEnabled" (for logging)
 * @param field         the volatile {@link Field} on the bean — written via reflection on swap
 * @param ref           internal AtomicReference for CAS dedup and state snapshots (not the read path)
 * @param targetType    e.g., boolean.class, String.class, int.class
 * @param key           e.g., "feature.newCheckout.enabled"
 * @param sourceUri     e.g., "file:///etc/myapp/config.yml"
 * @param sensitive     from {@code @Marz(sensitive=true)} — mask in logs
 * @since 1.0.0
 */
public record FieldBinding(
        Object bean,
        String beanClassName,
        String fieldName,
        Field field,
        AtomicReference<Object> ref,
        Class<?> targetType,
        String key,
        String sourceUri,
        boolean sensitive
) {}
