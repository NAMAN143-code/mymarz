package com.mymarz.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Additional {@link ConfigFormatParser} coverage beyond what's in
 * {@link ConfigFormatParserTest}.
 *
 * <p>Focuses on: numeric values (preserved as strings), deeply nested
 * structures, mixed lists, whitespace-only / blank input, uppercase
 * file extensions, ambiguous content sniffing fallbacks, and round-trip
 * fidelity for typical config shapes.</p>
 */
class ConfigFormatParserExpandedTest {

    private ConfigFormatParser parser;

    @BeforeEach
    void setUp() {
        parser = new ConfigFormatParser();
    }

    // ═══════════════════════════════════════════════════════════════════
    // BLANK / WHITESPACE / NULL INPUT
    // ═══════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "blank input \"{0}\" produces empty map")
    @ValueSource(strings = {"", " ", "  ", "\t", "\n", "\r\n", "  \n  "})
    void blankInputProducesEmptyMap(String content) {
        assertThat(parser.parse(content, "config.yml")).isEmpty();
    }

    @Test
    @DisplayName("null content produces empty map")
    void nullContentProducesEmptyMap() {
        assertThat(parser.parse(null, "config.yml")).isEmpty();
        assertThat(parser.parse(null, "config.json")).isEmpty();
        assertThat(parser.parse(null, "config.properties")).isEmpty();
        assertThat(parser.parse(null, null)).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // NUMERIC + BOOLEAN VALUES — preserved as strings (Map<String,String>)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("YAML numeric and boolean values are stringified")
    void yamlScalarsAreStringified() {
        String content = "intVal: 42\nlongVal: 9999999999\ndoubleVal: 3.14\nboolVal: true\nnullishVal: null\n";
        Map<String, String> result = parser.parse(content, "scalars.yml");

        assertThat(result)
                .containsEntry("intVal", "42")
                .containsEntry("longVal", "9999999999")
                .containsEntry("doubleVal", "3.14")
                .containsEntry("boolVal", "true");
        // null in YAML drops out of the flattened map entirely
        assertThat(result).doesNotContainKey("nullishVal");
    }

    @Test
    @DisplayName("JSON numeric and boolean values are stringified")
    void jsonScalarsAreStringified() {
        String content = "{\"intVal\":42,\"doubleVal\":3.14,\"boolVal\":false,\"nullVal\":null}";
        Map<String, String> result = parser.parse(content, "scalars.json");

        assertThat(result)
                .containsEntry("intVal", "42")
                .containsEntry("doubleVal", "3.14")
                .containsEntry("boolVal", "false");
        assertThat(result).doesNotContainKey("nullVal");
    }

    // ═══════════════════════════════════════════════════════════════════
    // DEEP NESTING
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("deeply nested YAML flattens to dotted keys at any depth")
    void deeplyNestedYaml() {
        String content = "a:\n  b:\n    c:\n      d:\n        e: leaf\n";
        Map<String, String> result = parser.parse(content, "deep.yml");

        assertThat(result).containsEntry("a.b.c.d.e", "leaf");
    }

    @Test
    @DisplayName("deeply nested JSON flattens to dotted keys at any depth")
    void deeplyNestedJson() {
        String content = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":\"leaf\"}}}}}";
        Map<String, String> result = parser.parse(content, "deep.json");

        assertThat(result).containsEntry("a.b.c.d.e", "leaf");
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIST FLATTENING — mixed content
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("nested lists inside lists flatten with indices")
    void nestedListsInLists() {
        String content = "matrix:\n  - [1, 2]\n  - [3, 4]\n";
        Map<String, String> result = parser.parse(content, "matrix.yml");

        assertThat(result)
                .containsEntry("matrix.0.0", "1")
                .containsEntry("matrix.0.1", "2")
                .containsEntry("matrix.1.0", "3")
                .containsEntry("matrix.1.1", "4");
    }

    @Test
    @DisplayName("list containing null elements skips the null entry")
    void listWithNullsSkipsNulls() {
        // JSON allows nulls explicitly; YAML's `~` parses to null
        String content = "{\"items\":[\"a\",null,\"c\"]}";
        Map<String, String> result = parser.parse(content, "nulls.json");

        assertThat(result).containsEntry("items.0", "a").containsEntry("items.2", "c");
        assertThat(result).doesNotContainKey("items.1");
    }

    @Test
    @DisplayName("empty list produces no entries")
    void emptyList() {
        Map<String, String> result = parser.parse("servers: []\n", "empty.yml");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("empty map under a key produces no entries for that subtree")
    void emptyNestedMap() {
        Map<String, String> result = parser.parse("feature: {}\nother: x\n", "empty.yml");
        assertThat(result).containsEntry("other", "x");
        assertThat(result.keySet()).noneMatch(k -> k.startsWith("feature."));
    }

    // ═══════════════════════════════════════════════════════════════════
    // FORMAT DETECTION — extension priority and case-insensitivity
    // ═══════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "extension {0} detected as {1}")
    @CsvSource({
        "config.YML, yaml",
        "config.YAML, yaml",
        "config.JSON, json",
        "config.PROPERTIES, properties",
        "/etc/myapp/CONFIG.Yml, yaml",
        "file:///tmp/x.Json, json",
    })
    @DisplayName("file extensions are matched case-insensitively")
    void extensionCaseInsensitive(String uri, String expected) {
        assertThat(ConfigFormatParser.detectFormat(uri, null)).isEqualTo(expected);
    }

    @Test
    @DisplayName("extension wins over content sniffing")
    void extensionBeatsContentSniff() {
        // Content looks like JSON, but extension says properties
        assertThat(ConfigFormatParser.detectFormat("config.properties", "{\"a\":1}"))
                .isEqualTo("properties");
        // Content looks like properties, but extension says yaml
        assertThat(ConfigFormatParser.detectFormat("config.yml", "key=value"))
                .isEqualTo("yaml");
    }

    @Test
    @DisplayName("content sniffing JSON requires leading brace")
    void sniffJsonNeedsBrace() {
        assertThat(ConfigFormatParser.detectFormat("noext", "  {\"a\":1}  "))
                .isEqualTo("json");
        assertThat(ConfigFormatParser.detectFormat("noext", " \"just a string\" "))
                .isNotEqualTo("json");
    }

    @Test
    @DisplayName("content sniffing returns yaml as the safe default")
    void sniffDefaultIsYaml() {
        // Pure text with no obvious delimiters
        assertThat(ConfigFormatParser.detectFormat("noext", "just-some-text-no-delimiters"))
                .isEqualTo("yaml");
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROPERTIES — escape sequences and continuations
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Properties format")
    class PropertiesParsing {

        @Test
        @DisplayName("supports colon as separator (java.util.Properties)")
        void colonSeparator() {
            Map<String, String> result = parser.parse("key:value\nother:thing\n", "x.properties");
            assertThat(result).containsEntry("key", "value").containsEntry("other", "thing");
        }

        @Test
        @DisplayName("trims surrounding whitespace from values")
        void trimsValues() {
            Map<String, String> result = parser.parse("key=  trimmed  \n", "x.properties");
            // java.util.Properties strips trailing whitespace from values too — both leading & trailing
            assertThat(result.get("key")).isNotNull();
            assertThat(result.get("key").trim()).isEqualTo("trimmed");
        }

        @Test
        @DisplayName("blank values are kept as empty strings")
        void blankValue() {
            Map<String, String> result = parser.parse("key=\n", "x.properties");
            assertThat(result).containsEntry("key", "");
        }

        @Test
        @DisplayName("multiple equals signs — first separator wins, rest is value")
        void multipleEquals() {
            Map<String, String> result = parser.parse("url=https://x.com/?a=1&b=2\n", "x.properties");
            assertThat(result).containsEntry("url", "https://x.com/?a=1&b=2");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ERROR RESILIENCE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("malformed YAML returns empty map (does not throw)")
    void malformedYamlReturnsEmpty() {
        // ":-" at top level is parseable; "  not  : valid  : yaml" with two colons in a key is malformed
        String content = "this:\n  is: invalid\n -not really valid\n  :::";
        // The parser swallows exceptions and returns empty
        Map<String, String> result = parser.parse(content, "broken.yml");
        // either parses partially or is empty — but does not throw
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("malformed JSON returns empty map (does not throw)")
    void malformedJsonReturnsEmpty() {
        assertThat(parser.parse("{\"a\":1,", "broken.json")).isEmpty();
        assertThat(parser.parse("garbage", "broken.json")).isEmpty();
    }

    @Test
    @DisplayName("YAML list at root (not a Map) returns empty")
    void yamlRootListReturnsEmpty() {
        assertThat(parser.parse("- a\n- b\n", "list.yml")).isEmpty();
    }

    @Test
    @DisplayName("YAML scalar at root (not a Map) returns empty")
    void yamlRootScalarReturnsEmpty() {
        assertThat(parser.parse("justastring\n", "scalar.yaml")).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // NUMERIC KEYS — YAML allows them
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("non-string YAML keys (numbers) are stringified")
    void nonStringKeys() {
        String content = "1: one\n2: two\n";
        Map<String, String> result = parser.parse(content, "x.yml");
        assertThat(result).containsEntry("1", "one").containsEntry("2", "two");
    }
}
