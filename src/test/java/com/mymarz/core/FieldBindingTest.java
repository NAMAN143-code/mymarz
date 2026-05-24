package com.mymarz.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FieldBindingTest {

    @Test
    void recordHoldsAllFields() {
        AtomicReference<Object> ref = new AtomicReference<>(false);
        Object bean = new Object();

        FieldBinding binding = new FieldBinding(
                bean, "PaymentService", "checkoutEnabled",
                null, ref, boolean.class, "feature.checkout",
                "file:///config.yml", false
        );

        assertThat(binding.bean()).isSameAs(bean);
        assertThat(binding.beanClassName()).isEqualTo("PaymentService");
        assertThat(binding.fieldName()).isEqualTo("checkoutEnabled");
        assertThat(binding.ref()).isSameAs(ref);
        assertThat(binding.targetType()).isEqualTo(boolean.class);
        assertThat(binding.key()).isEqualTo("feature.checkout");
        assertThat(binding.sourceUri()).isEqualTo("file:///config.yml");
        assertThat(binding.sensitive()).isFalse();
    }

    @Test
    void sensitiveBinding() {
        FieldBinding binding = new FieldBinding(
                new Object(), "SecretService", "apiKey",
                null, new AtomicReference<>("secret-123"), String.class,
                "api.key", "file:///secrets.yml", true
        );

        assertThat(binding.sensitive()).isTrue();
    }

    @Test
    void refIsSwappable() {
        AtomicReference<Object> ref = new AtomicReference<>(false);
        FieldBinding binding = new FieldBinding(
                new Object(), "TestBean", "field",
                null, ref, boolean.class, "key",
                "file:///config.yml", false
        );

        // Swap via the ref
        Object old = binding.ref().getAndSet(true);

        assertThat(old).isEqualTo(false);
        assertThat(binding.ref().get()).isEqualTo(true);
    }

    @Test
    void recordIsImmutable() {
        AtomicReference<Object> ref = new AtomicReference<>("v1");
        FieldBinding b1 = new FieldBinding(
                new Object(), "Bean", "field", null, ref,
                String.class, "key", "file:///c.yml", false
        );

        // The binding record itself is immutable — we can only swap the ref's value
        ref.set("v2");
        assertThat(b1.ref().get()).isEqualTo("v2");
        assertThat(b1.key()).isEqualTo("key"); // Unchanged
    }
}
