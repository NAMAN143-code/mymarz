package com.mymarz.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expanded {@link FieldBinding} coverage:
 * <ul>
 *     <li>Record equals/hashCode/toString contract</li>
 *     <li>Null components are allowed (record is just a data holder)</li>
 *     <li>Holding a real {@link Field} reference works</li>
 *     <li>AtomicReference identity is preserved across getters</li>
 * </ul>
 */
class FieldBindingExpandedTest {

    @SuppressWarnings("unused")
    private volatile String sampleField = "hello";

    // ═══════════════════════════════════════════════════════════════════
    // RECORD CONTRACT — equals / hashCode / toString
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("two bindings with identical components are equal")
    void equalsByValue() {
        AtomicReference<Object> ref = new AtomicReference<>("v");
        Object bean = new Object();

        FieldBinding a = new FieldBinding(
                bean, "Bean", "field", null, ref, String.class,
                "k", "src", false);
        FieldBinding b = new FieldBinding(
                bean, "Bean", "field", null, ref, String.class,
                "k", "src", false);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("bindings with different keys are not equal")
    void differentKeysNotEqual() {
        AtomicReference<Object> ref = new AtomicReference<>("v");
        FieldBinding a = new FieldBinding(
                new Object(), "B", "f", null, ref, String.class, "k1", "src", false);
        FieldBinding b = new FieldBinding(
                new Object(), "B", "f", null, ref, String.class, "k2", "src", false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("toString contains the key for diagnostics")
    void toStringContainsKey() {
        FieldBinding b = new FieldBinding(
                new Object(), "Bean", "field", null, new AtomicReference<>(),
                String.class, "my.config.key", "src", false);

        assertThat(b.toString()).contains("my.config.key");
    }

    @Test
    @DisplayName("toString does not throw on null components")
    void toStringNoNpe() {
        FieldBinding b = new FieldBinding(
                null, null, null, null, null, null, null, null, false);
        // The record's auto-generated toString must handle nulls gracefully
        assertThat(b.toString()).isNotNull();
    }

    @Test
    @DisplayName("not equal to non-FieldBinding type")
    void notEqualToOtherType() {
        FieldBinding b = new FieldBinding(
                new Object(), "B", "f", null, new AtomicReference<>(),
                String.class, "k", "src", false);
        assertThat(b).isNotEqualTo("some-string");
        assertThat(b).isNotEqualTo(null);
    }

    // ═══════════════════════════════════════════════════════════════════
    // NULL COMPONENTS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("record allows null components — it's just a holder")
    void allowsNullComponents() {
        // The record does not validate inputs — that's the responsibility of callers.
        // Verifying this here so a future change to add validation has a failing test.
        FieldBinding b = new FieldBinding(
                null, null, null, null, null, null, null, null, true);

        assertThat(b.bean()).isNull();
        assertThat(b.beanClassName()).isNull();
        assertThat(b.fieldName()).isNull();
        assertThat(b.field()).isNull();
        assertThat(b.ref()).isNull();
        assertThat(b.targetType()).isNull();
        assertThat(b.key()).isNull();
        assertThat(b.sourceUri()).isNull();
        assertThat(b.sensitive()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════
    // REAL FIELD REFERENCE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("holds a real reflective Field reference")
    void realFieldReference() throws NoSuchFieldException {
        Field f = FieldBindingExpandedTest.class.getDeclaredField("sampleField");

        FieldBinding b = new FieldBinding(
                this, "FieldBindingExpandedTest", "sampleField", f,
                new AtomicReference<>("hello"), String.class,
                "sample", "file:///c.yml", false);

        assertThat(b.field()).isSameAs(f);
        assertThat(b.field().getName()).isEqualTo("sampleField");
    }

    // ═══════════════════════════════════════════════════════════════════
    // ATOMIC REFERENCE IDENTITY
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ref() returns the same AtomicReference instance on every call")
    void refIdentityStable() {
        AtomicReference<Object> ref = new AtomicReference<>("v");
        FieldBinding b = new FieldBinding(
                new Object(), "B", "f", null, ref, String.class,
                "k", "src", false);

        assertThat(b.ref()).isSameAs(b.ref()).isSameAs(ref);
    }

    @Test
    @DisplayName("updates through the binding's ref are visible via the original ref handle")
    void refUpdatesVisible() {
        AtomicReference<Object> ref = new AtomicReference<>("v1");
        FieldBinding b = new FieldBinding(
                new Object(), "B", "f", null, ref, String.class,
                "k", "src", false);

        b.ref().set("v2");

        assertThat(ref.get()).isEqualTo("v2");
    }

    // ═══════════════════════════════════════════════════════════════════
    // TARGET TYPE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("targetType can be primitive class")
    void primitiveTargetType() {
        FieldBinding b = new FieldBinding(
                new Object(), "B", "f", null, new AtomicReference<>(0),
                int.class, "k", "src", false);
        assertThat(b.targetType()).isEqualTo(int.class);
        assertThat(b.targetType().isPrimitive()).isTrue();
    }

    @Test
    @DisplayName("targetType can be wrapper class")
    void wrapperTargetType() {
        FieldBinding b = new FieldBinding(
                new Object(), "B", "f", null, new AtomicReference<>(0),
                Integer.class, "k", "src", false);
        assertThat(b.targetType()).isEqualTo(Integer.class);
        assertThat(b.targetType().isPrimitive()).isFalse();
    }
}
