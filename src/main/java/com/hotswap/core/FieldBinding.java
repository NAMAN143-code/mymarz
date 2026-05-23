package com.hotswap.core;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable binding between a config key and a field's in-memory location.
 *
 * <p>Created once during {@link HotSwapBeanPostProcessor} scanning.
 * Never modified after creation. The {@code ref} field is the
 * {@link AtomicReference} that application code reads from — this is
 * what gets swapped when a config value changes.</p>
 *
 * <p>Part of the reverse index in {@link HotSwapRegistry}:
 * config key → List&lt;FieldBinding&gt;</p>
 *
 * @param bean          the Spring bean instance containing the field
 * @param beanClassName e.g., "com.acme.PaymentService" (for logging)
 * @param fieldName     e.g., "newCheckoutEnabled" (for logging)
 * @param ref           the actual memory location — this is what gets swapped
 * @param targetType    e.g., boolean.class, String.class, int.class
 * @param key           e.g., "feature.newCheckout.enabled"
 * @param sourceUri     e.g., "file:///etc/myapp/config.yml"
 * @param sensitive     from {@code @HotSwap(sensitive=true)} — mask in logs
 * @since 1.0.0
 */
public record FieldBinding(
        Object bean,
        String beanClassName,
        String fieldName,
        AtomicReference<Object> ref,
        Class<?> targetType,
        String key,
        String sourceUri,
        boolean sensitive
) {}
