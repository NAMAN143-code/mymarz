package com.mymarz.type;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mymarz.annotation.MarzType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Expanded {@link TypeCoercer} coverage:
 * <ul>
 *     <li>JSON deserialization for complex field types (lists, maps, POJOs)</li>
 *     <li>Numeric edge cases (overflow, underflow, decimal vs int)</li>
 *     <li>Boolean variants explicitly enumerated</li>
 *     <li>Custom ObjectMapper injection</li>
 *     <li>Error messages contain context (field name and value)</li>
 * </ul>
 */
class TypeCoercerExpandedTest {

    private TypeCoercer coercer;

    @SuppressWarnings("unused") private boolean boolField;
    @SuppressWarnings("unused") private int intField;
    @SuppressWarnings("unused") private long longField;
    @SuppressWarnings("unused") private double doubleField;
    @SuppressWarnings("unused") private String stringField;
    @SuppressWarnings("unused") private List<String> listField;
    @SuppressWarnings("unused") private Map<String, Integer> mapField;
    @SuppressWarnings("unused") private SimplePojo pojoField;

    @BeforeEach
    void setUp() {
        coercer = new TypeCoercer();
    }

    // ═══════════════════════════════════════════════════════════════════
    // JSON COERCION — the catch-all path for complex types
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("JSON coercion")
    class JsonCoercion {

        @Test
        @DisplayName("Class-based coerce: deserializes JSON array to List")
        @SuppressWarnings("unchecked")
        void classBasedJsonList() {
            Object result = coercer.coerce("[\"a\",\"b\",\"c\"]", List.class);
            assertThat(result).isInstanceOf(List.class);
            List<Object> list = (List<Object>) result;
            assertThat(list).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("Class-based coerce: deserializes JSON object to Map")
        @SuppressWarnings("unchecked")
        void classBasedJsonMap() {
            Object result = coercer.coerce("{\"a\":1,\"b\":2}", Map.class);
            assertThat(result).isInstanceOf(Map.class);
            Map<String, Object> map = (Map<String, Object>) result;
            assertThat(map).containsEntry("a", 1).containsEntry("b", 2);
        }

        @Test
        @DisplayName("Class-based coerce: deserializes JSON to POJO")
        void classBasedJsonPojo() {
            Object result = coercer.coerce("{\"name\":\"alice\",\"age\":30}", SimplePojo.class);
            assertThat(result).isInstanceOf(SimplePojo.class);
            SimplePojo pojo = (SimplePojo) result;
            assertThat(pojo.name).isEqualTo("alice");
            assertThat(pojo.age).isEqualTo(30);
        }

        @Test
        @DisplayName("Class-based coerce: malformed JSON throws MarzTypeException")
        void classBasedJsonInvalid() {
            assertThatThrownBy(() -> coercer.coerce("{not json}", SimplePojo.class))
                    .isInstanceOf(MarzTypeException.class)
                    .hasMessageContaining("Failed to coerce");
        }

        @Test
        @DisplayName("Annotation-based JSON coerce uses the field's declared type")
        @SuppressWarnings("unchecked")
        void annotationBasedJsonList() throws Exception {
            Field f = getField("listField");
            Object result = coercer.coerce("[\"x\",\"y\"]", MarzType.JSON, f);
            assertThat(result).isInstanceOf(List.class);
            List<Object> list = (List<Object>) result;
            assertThat(list).containsExactly("x", "y");
        }

        @Test
        @DisplayName("INFERRED on complex field type defaults to JSON")
        void inferredOnComplexField() throws Exception {
            Field f = getField("mapField");
            Object result = coercer.coerce("{\"a\":1}", MarzType.INFERRED, f);
            assertThat(result).isInstanceOf(Map.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CUSTOM OBJECT MAPPER
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("constructor accepts a custom ObjectMapper")
    void customObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        TypeCoercer custom = new TypeCoercer(mapper);

        Object result = custom.coerce("{\"name\":\"bob\"}", SimplePojo.class);
        assertThat(result).isInstanceOf(SimplePojo.class);
        assertThat(((SimplePojo) result).name).isEqualTo("bob");
    }

    // ═══════════════════════════════════════════════════════════════════
    // BOOLEAN — every spelling
    // ═══════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "\"{0}\" coerces to true")
    @ValueSource(strings = {"true", "TRUE", "True", "tRuE", "  true  ", "1", "yes", "YES", "Yes"})
    void booleanTruthy(String raw) {
        assertThat(coercer.coerce(raw, boolean.class)).isEqualTo(true);
    }

    @ParameterizedTest(name = "\"{0}\" coerces to false")
    @ValueSource(strings = {"false", "FALSE", "False", "  false  ", "0", "no", "NO", "No"})
    void booleanFalsy(String raw) {
        assertThat(coercer.coerce(raw, boolean.class)).isEqualTo(false);
    }

    @ParameterizedTest(name = "boolean coercion rejects \"{0}\"")
    @ValueSource(strings = {"maybe", "tru", "ye", "2", "on", "off", ""})
    void booleanRejected(String raw) {
        assertThatThrownBy(() -> coercer.coerce(raw, boolean.class))
                .isInstanceOf(MarzTypeException.class);
    }

    // ═══════════════════════════════════════════════════════════════════
    // NUMERIC EDGE CASES
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Numeric edges")
    class Numeric {

        @Test
        @DisplayName("Integer.MAX_VALUE parses correctly")
        void intMax() {
            assertThat(coercer.coerce(String.valueOf(Integer.MAX_VALUE), int.class))
                    .isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Integer.MIN_VALUE parses correctly")
        void intMin() {
            assertThat(coercer.coerce(String.valueOf(Integer.MIN_VALUE), int.class))
                    .isEqualTo(Integer.MIN_VALUE);
        }

        @Test
        @DisplayName("int overflow throws MarzTypeException")
        void intOverflow() {
            assertThatThrownBy(() -> coercer.coerce("9999999999", int.class))
                    .isInstanceOf(MarzTypeException.class)
                    .hasMessageContaining("9999999999");
        }

        @Test
        @DisplayName("Long.MAX_VALUE parses correctly")
        void longMax() {
            assertThat(coercer.coerce(String.valueOf(Long.MAX_VALUE), long.class))
                    .isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("long overflow throws MarzTypeException")
        void longOverflow() {
            assertThatThrownBy(() -> coercer.coerce("99999999999999999999", long.class))
                    .isInstanceOf(MarzTypeException.class);
        }

        @Test
        @DisplayName("double parses scientific notation")
        void doubleScientific() {
            assertThat(coercer.coerce("1.5e2", double.class)).isEqualTo(150.0);
            assertThat(coercer.coerce("-3.14E-2", double.class)).isEqualTo(-0.0314);
        }

        @Test
        @DisplayName("double accepts whole numbers")
        void doubleWholeNumber() {
            assertThat(coercer.coerce("42", double.class)).isEqualTo(42.0);
        }

        @Test
        @DisplayName("double Infinity and NaN parse")
        void doubleSpecial() {
            assertThat((double) coercer.coerce("Infinity", double.class)).isInfinite();
            assertThat((double) coercer.coerce("NaN", double.class)).isNaN();
        }

        @Test
        @DisplayName("int with decimal point is rejected")
        void intRejectsDecimal() {
            assertThatThrownBy(() -> coercer.coerce("3.14", int.class))
                    .isInstanceOf(MarzTypeException.class);
        }

        @Test
        @DisplayName("Integer.parseInt does not accept hex/octal prefixes")
        void intRejectsHex() {
            assertThatThrownBy(() -> coercer.coerce("0xFF", int.class))
                    .isInstanceOf(MarzTypeException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ERROR MESSAGE CONTENT
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("annotation-based error mentions field name in message")
    void errorMessageHasFieldName() throws Exception {
        Field f = getField("intField");
        assertThatThrownBy(() -> coercer.coerce("not-a-number", MarzType.INTEGER, f))
                .isInstanceOf(MarzTypeException.class)
                .hasMessageContaining("intField")
                .hasMessageContaining("INTEGER");
    }

    @Test
    @DisplayName("class-based error includes the raw value and target type")
    void classBasedErrorIncludesValue() {
        assertThatThrownBy(() -> coercer.coerce("abc", int.class))
                .isInstanceOf(MarzTypeException.class)
                .hasMessageContaining("abc")
                .hasMessageContaining("int");
    }

    // ═══════════════════════════════════════════════════════════════════
    // STRING — passthrough semantics
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("String coercion preserves leading and trailing whitespace")
    void stringPreservesWhitespace() {
        assertThat(coercer.coerce("  spaces  ", String.class)).isEqualTo("  spaces  ");
    }

    @Test
    @DisplayName("String coercion preserves empty string")
    void stringEmpty() {
        assertThat(coercer.coerce("", String.class)).isEqualTo("");
    }

    @Test
    @DisplayName("String coercion preserves unicode")
    void stringUnicode() {
        assertThat(coercer.coerce("héllo 世界 🌍", String.class)).isEqualTo("héllo 世界 🌍");
    }

    // ═══════════════════════════════════════════════════════════════════
    // INFERRED MODE — coverage of switch arms
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("INFERRED on long field returns Long")
    void inferredLong() throws Exception {
        assertThat(coercer.coerce("100", MarzType.INFERRED, getField("longField")))
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("INFERRED on double field returns Double")
    void inferredDouble() throws Exception {
        assertThat(coercer.coerce("2.5", MarzType.INFERRED, getField("doubleField")))
                .isEqualTo(2.5);
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXPLICIT MarzType arms — defensive coverage
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("explicit STRING type passes value through unchanged")
    void explicitString() throws Exception {
        assertThat(coercer.coerce("anything", MarzType.STRING, getField("stringField")))
                .isEqualTo("anything");
    }

    @Test
    @DisplayName("explicit LONG type parses long values")
    void explicitLong() throws Exception {
        assertThat(coercer.coerce("123456789", MarzType.LONG, getField("longField")))
                .isEqualTo(123456789L);
    }

    @Test
    @DisplayName("explicit DOUBLE type parses double values")
    void explicitDouble() throws Exception {
        assertThat(coercer.coerce("0.5", MarzType.DOUBLE, getField("doubleField")))
                .isEqualTo(0.5);
    }

    private Field getField(String name) throws NoSuchFieldException {
        return TypeCoercerExpandedTest.class.getDeclaredField(name);
    }

    /** Public POJO so Jackson can construct it during deserialization tests. */
    public static class SimplePojo {
        public String name;
        public int age;

        public SimplePojo() {}
    }
}
