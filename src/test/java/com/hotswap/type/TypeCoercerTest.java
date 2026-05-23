package com.hotswap.type;

import com.hotswap.annotation.HotSwapType;
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
        assertThat(coercer.coerce("true", HotSwapType.BOOLEAN, field)).isEqualTo(true);
        assertThat(coercer.coerce("TRUE", HotSwapType.BOOLEAN, field)).isEqualTo(true);
        assertThat(coercer.coerce("1", HotSwapType.BOOLEAN, field)).isEqualTo(true);
        assertThat(coercer.coerce("yes", HotSwapType.BOOLEAN, field)).isEqualTo(true);
    }

    @Test
    void coerceBoolean_false() throws Exception {
        Field field = getField("boolField");
        assertThat(coercer.coerce("false", HotSwapType.BOOLEAN, field)).isEqualTo(false);
        assertThat(coercer.coerce("0", HotSwapType.BOOLEAN, field)).isEqualTo(false);
        assertThat(coercer.coerce("no", HotSwapType.BOOLEAN, field)).isEqualTo(false);
    }

    @Test
    void coerceBoolean_invalid_throws() throws Exception {
        Field field = getField("boolField");
        assertThatThrownBy(() -> coercer.coerce("maybe", HotSwapType.BOOLEAN, field))
                .isInstanceOf(HotSwapTypeException.class);
    }

    // --- String coercion ---

    @Test
    void coerceString() throws Exception {
        Field field = getField("stringField");
        assertThat(coercer.coerce("hello world", HotSwapType.STRING, field)).isEqualTo("hello world");
    }

    // --- Integer coercion ---

    @Test
    void coerceInteger() throws Exception {
        Field field = getField("intField");
        assertThat(coercer.coerce("42", HotSwapType.INTEGER, field)).isEqualTo(42);
        assertThat(coercer.coerce(" 100 ", HotSwapType.INTEGER, field)).isEqualTo(100);
    }

    @Test
    void coerceInteger_invalid_throws() throws Exception {
        Field field = getField("intField");
        assertThatThrownBy(() -> coercer.coerce("not-a-number", HotSwapType.INTEGER, field))
                .isInstanceOf(HotSwapTypeException.class);
    }

    // --- Long coercion ---

    @Test
    void coerceLong() throws Exception {
        Field field = getField("longField");
        assertThat(coercer.coerce("9999999999", HotSwapType.LONG, field)).isEqualTo(9999999999L);
    }

    // --- Double coercion ---

    @Test
    void coerceDouble() throws Exception {
        Field field = getField("doubleField");
        assertThat(coercer.coerce("3.14", HotSwapType.DOUBLE, field)).isEqualTo(3.14);
    }

    // --- Null handling ---

    @Test
    void coerceNull_returnsNull() throws Exception {
        Field field = getField("stringField");
        assertThat(coercer.coerce(null, HotSwapType.STRING, field)).isNull();
    }

    // --- Type inference ---

    @Test
    void inferType_boolean() throws Exception {
        assertThat(coercer.inferType(getField("boolField"))).isEqualTo(HotSwapType.BOOLEAN);
        assertThat(coercer.inferType(getField("boolWrapperField"))).isEqualTo(HotSwapType.BOOLEAN);
    }

    @Test
    void inferType_string() throws Exception {
        assertThat(coercer.inferType(getField("stringField"))).isEqualTo(HotSwapType.STRING);
    }

    @Test
    void inferType_integer() throws Exception {
        assertThat(coercer.inferType(getField("intField"))).isEqualTo(HotSwapType.INTEGER);
        assertThat(coercer.inferType(getField("intWrapperField"))).isEqualTo(HotSwapType.INTEGER);
    }

    @Test
    void inferType_long() throws Exception {
        assertThat(coercer.inferType(getField("longField"))).isEqualTo(HotSwapType.LONG);
    }

    @Test
    void inferType_double() throws Exception {
        assertThat(coercer.inferType(getField("doubleField"))).isEqualTo(HotSwapType.DOUBLE);
    }

    @Test
    void inferType_complexType_defaultsToJson() throws Exception {
        assertThat(coercer.inferType(getField("listField"))).isEqualTo(HotSwapType.JSON);
    }

    // --- INFERRED mode end-to-end ---

    @Test
    void coerceInferred_usesFieldType() throws Exception {
        assertThat(coercer.coerce("true", HotSwapType.INFERRED, getField("boolField"))).isEqualTo(true);
        assertThat(coercer.coerce("42", HotSwapType.INFERRED, getField("intField"))).isEqualTo(42);
        assertThat(coercer.coerce("hello", HotSwapType.INFERRED, getField("stringField"))).isEqualTo("hello");
    }

    // --- Class-based coercion (used by HotSwapRegistry.onSourceChange) ---

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
                .isInstanceOf(HotSwapTypeException.class);
    }

    @Test
    void coerceByClass_invalidInt_throws() {
        assertThatThrownBy(() -> coercer.coerce("abc", int.class))
                .isInstanceOf(HotSwapTypeException.class);
    }

    private Field getField(String name) throws NoSuchFieldException {
        return TypeCoercerTest.class.getDeclaredField(name);
    }
}
