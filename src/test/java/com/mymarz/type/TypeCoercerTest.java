package com.mymarz.type;

import com.mymarz.annotation.MarzType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TypeCoercerTest {

    private TypeCoercer coercer;

    // Test fields for reflection
    @SuppressWarnings("unused") private boolean boolField;
    @SuppressWarnings("unused") private Boolean boolWrapperField;
    @SuppressWarnings("unused") private String stringField;
    @SuppressWarnings("unused") private int intField;
    @SuppressWarnings("unused") private Integer intWrapperField;
    @SuppressWarnings("unused") private long longField;
    @SuppressWarnings("unused") private double doubleField;
    @SuppressWarnings("unused") private List<String> listField;

    @BeforeEach
    void setUp() {
        coercer = new TypeCoercer();
    }

    // --- Boolean coercion ---

    @Test
    void coerceBoolean_true() throws Exception {
        Field field = getField("boolField");
        assertThat(coercer.coerce("true", MarzType.BOOLEAN, field)).isEqualTo(true);
        assertThat(coercer.coerce("TRUE", MarzType.BOOLEAN, field)).isEqualTo(true);
        assertThat(coercer.coerce("1", MarzType.BOOLEAN, field)).isEqualTo(true);
        assertThat(coercer.coerce("yes", MarzType.BOOLEAN, field)).isEqualTo(true);
    }

    @Test
    void coerceBoolean_false() throws Exception {
        Field field = getField("boolField");
        assertThat(coercer.coerce("false", MarzType.BOOLEAN, field)).isEqualTo(false);
        assertThat(coercer.coerce("0", MarzType.BOOLEAN, field)).isEqualTo(false);
        assertThat(coercer.coerce("no", MarzType.BOOLEAN, field)).isEqualTo(false);
    }

    @Test
    void coerceBoolean_invalid_throws() throws Exception {
        Field field = getField("boolField");
        assertThatThrownBy(() -> coercer.coerce("maybe", MarzType.BOOLEAN, field))
                .isInstanceOf(MarzTypeException.class);
    }

    // --- String coercion ---

    @Test
    void coerceString() throws Exception {
        Field field = getField("stringField");
        assertThat(coercer.coerce("hello world", MarzType.STRING, field)).isEqualTo("hello world");
    }

    // --- Integer coercion ---

    @Test
    void coerceInteger() throws Exception {
        Field field = getField("intField");
        assertThat(coercer.coerce("42", MarzType.INTEGER, field)).isEqualTo(42);
        assertThat(coercer.coerce(" 100 ", MarzType.INTEGER, field)).isEqualTo(100);
    }

    @Test
    void coerceInteger_invalid_throws() throws Exception {
        Field field = getField("intField");
        assertThatThrownBy(() -> coercer.coerce("not-a-number", MarzType.INTEGER, field))
                .isInstanceOf(MarzTypeException.class);
    }

    // --- Long coercion ---

    @Test
    void coerceLong() throws Exception {
        Field field = getField("longField");
        assertThat(coercer.coerce("9999999999", MarzType.LONG, field)).isEqualTo(9999999999L);
    }

    // --- Double coercion ---

    @Test
    void coerceDouble() throws Exception {
        Field field = getField("doubleField");
        assertThat(coercer.coerce("3.14", MarzType.DOUBLE, field)).isEqualTo(3.14);
    }

    // --- Null handling ---

    @Test
    void coerceNull_returnsNull() throws Exception {
        Field field = getField("stringField");
        assertThat(coercer.coerce(null, MarzType.STRING, field)).isNull();
    }

    // --- Type inference ---

    @Test
    void inferType_boolean() throws Exception {
        assertThat(coercer.inferType(getField("boolField"))).isEqualTo(MarzType.BOOLEAN);
        assertThat(coercer.inferType(getField("boolWrapperField"))).isEqualTo(MarzType.BOOLEAN);
    }

    @Test
    void inferType_string() throws Exception {
        assertThat(coercer.inferType(getField("stringField"))).isEqualTo(MarzType.STRING);
    }

    @Test
    void inferType_integer() throws Exception {
        assertThat(coercer.inferType(getField("intField"))).isEqualTo(MarzType.INTEGER);
        assertThat(coercer.inferType(getField("intWrapperField"))).isEqualTo(MarzType.INTEGER);
    }

    @Test
    void inferType_long() throws Exception {
        assertThat(coercer.inferType(getField("longField"))).isEqualTo(MarzType.LONG);
    }

    @Test
    void inferType_double() throws Exception {
        assertThat(coercer.inferType(getField("doubleField"))).isEqualTo(MarzType.DOUBLE);
    }

    @Test
    void inferType_complexType_defaultsToJson() throws Exception {
        assertThat(coercer.inferType(getField("listField"))).isEqualTo(MarzType.JSON);
    }

    // --- INFERRED mode end-to-end ---

    @Test
    void coerceInferred_usesFieldType() throws Exception {
        assertThat(coercer.coerce("true", MarzType.INFERRED, getField("boolField"))).isEqualTo(true);
        assertThat(coercer.coerce("42", MarzType.INFERRED, getField("intField"))).isEqualTo(42);
        assertThat(coercer.coerce("hello", MarzType.INFERRED, getField("stringField"))).isEqualTo("hello");
    }

    // --- Class-based coercion (used by MarzRegistry.onSourceChange) ---

    @Test
    void coerceByClass_boolean() {
        assertThat(coercer.coerce("true", boolean.class)).isEqualTo(true);
        assertThat(coercer.coerce("false", Boolean.class)).isEqualTo(false);
        assertThat(coercer.coerce("yes", boolean.class)).isEqualTo(true);
    }

    @Test
    void coerceByClass_string() {
        assertThat(coercer.coerce("hello", String.class)).isEqualTo("hello");
    }

    @Test
    void coerceByClass_int() {
        assertThat(coercer.coerce("42", int.class)).isEqualTo(42);
        assertThat(coercer.coerce(" 100 ", Integer.class)).isEqualTo(100);
    }

    @Test
    void coerceByClass_long() {
        assertThat(coercer.coerce("9999999999", long.class)).isEqualTo(9999999999L);
    }

    @Test
    void coerceByClass_double() {
        assertThat(coercer.coerce("3.14", double.class)).isEqualTo(3.14);
    }

    @Test
    void coerceByClass_null() {
        assertThat(coercer.coerce(null, String.class)).isNull();
    }

    @Test
    void coerceByClass_invalidBoolean_throws() {
        assertThatThrownBy(() -> coercer.coerce("maybe", boolean.class))
                .isInstanceOf(MarzTypeException.class);
    }

    @Test
    void coerceByClass_invalidInt_throws() {
        assertThatThrownBy(() -> coercer.coerce("abc", int.class))
                .isInstanceOf(MarzTypeException.class);
    }

    private Field getField(String name) throws NoSuchFieldException {
        return TypeCoercerTest.class.getDeclaredField(name);
    }
}
