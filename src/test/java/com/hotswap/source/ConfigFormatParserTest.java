package com.hotswap.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConfigFormatParser}.
 */
class ConfigFormatParserTest {

    private ConfigFormatParser parser;

    @BeforeEach
    void setUp() {
        parser = new ConfigFormatParser();
    }

    // -------------------------------------------------------------------------
    // YAML parsing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("YAML parsing")
    class YamlParsing {

        @Test
        @DisplayName("parses flat YAML keys")
        void parseFlatYaml() {
            String content = "feature.dark-mode.enabled: true\nfeature.checkout.v2: false\n";
            Map<String, String> result = parser.parse(content, "config.yml");
            assertThat(result)
                    .containsEntry("feature.dark-mode.enabled", "true")
                    .containsEntry("feature.checkout.v2", "false");
        }

        @Test
        @DisplayName("flattens nested YAML to dot-notation")
        void parseNestedYaml() {
            String content = "feature:\n  dark-mode:\n    enabled: true\n  checkout:\n    v2: false\n";
            Map<String, String> result = parser.parse(content, "config.yml");
            assertThat(result)
                    .containsEntry("feature.dark-mode.enabled", "true")
                    .containsEntry("feature.checkout.v2", "false");
        }

        @Test
        @DisplayName("flattens YAML lists to indexed keys")
        void parseYamlWithLists() {
            String content = "servers:\n  - alpha\n  - beta\n  - gamma\n";
            Map<String, String> result = parser.parse(content, "config.yml");
            assertThat(result)
                    .containsEntry("servers.0", "alpha")
                    .containsEntry("servers.1", "beta")
                    .containsEntry("servers.2", "gamma");
        }

        @Test
        @DisplayName("flattens nested maps inside YAML lists")
        void parseYamlWithNestedMapsInLists() {
            String content = "databases:\n  - host: db1.example.com\n    port: 5432\n  - host: db2.example.com\n    port: 5433\n";
            Map<String, String> result = parser.parse(content, "config.yml");
            assertThat(result)
                    .containsEntry("databases.0.host", "db1.example.com")
                    .containsEntry("databases.0.port", "5432")
                    .containsEntry("databases.1.host", "db2.example.com")
                    .containsEntry("databases.1.port", "5433");
        }

        @Test
        @DisplayName("returns empty map for empty YAML")
        void parseEmptyYaml() {
            assertThat(parser.parse("", "config.yml")).isEmpty();
            assertThat(parser.parse(null, "config.yml")).isEmpty();
        }

        @Test
        @DisplayName("returns empty map for non-map YAML root")
        void parseNonMapRootYaml() {
            assertThat(parser.parse("- item1\n- item2\n", "config.yml")).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("JSON parsing")
    class JsonParsing {

        @Test
        @DisplayName("parses flat JSON")
        void parseFlatJson() {
            String content = "{\"feature.dark-mode.enabled\": \"true\", \"timeout\": \"30\"}";
            Map<String, String> result = parser.parse(content, "config.json");
            assertThat(result)
                    .containsEntry("feature.dark-mode.enabled", "true")
                    .containsEntry("timeout", "30");
        }

        @Test
        @DisplayName("flattens nested JSON to dot-notation")
        void parseNestedJson() {
            String content = "{\"feature\": {\"checkout\": {\"v2\": true}}, \"timeout\": 30}";
            Map<String, String> result = parser.parse(content, "config.json");
            assertThat(result)
                    .containsEntry("feature.checkout.v2", "true")
                    .containsEntry("timeout", "30");
        }

        @Test
        @DisplayName("returns empty map for invalid JSON")
        void parseInvalidJson() {
            assertThat(parser.parse("not json at all", "config.json")).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Properties parsing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName(".properties parsing")
    class PropertiesParsing {

        @Test
        @DisplayName("parses standard .properties format")
        void parseProperties() {
            String content = "feature.dark-mode.enabled=true\nfeature.checkout.v2=false\ntimeout=30\n";
            Map<String, String> result = parser.parse(content, "config.properties");
            assertThat(result)
                    .containsEntry("feature.dark-mode.enabled", "true")
                    .containsEntry("feature.checkout.v2", "false")
                    .containsEntry("timeout", "30");
        }

        @Test
        @DisplayName("ignores comments in .properties")
        void ignoresComments() {
            String content = "# This is a comment\nkey=value\n";
            Map<String, String> result = parser.parse(content, "config.properties");
            assertThat(result).containsEntry("key", "value").doesNotContainKey("# This is a comment");
        }
    }

    // -------------------------------------------------------------------------
    // Format detection
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Format detection")
    class FormatDetection {

        @Test
        @DisplayName("detects YAML from .yml extension")
        void detectYmlExtension() {
            assertThat(ConfigFormatParser.detectFormat("config.yml", null)).isEqualTo("yaml");
            assertThat(ConfigFormatParser.detectFormat("config.yaml", null)).isEqualTo("yaml");
        }

        @Test
        @DisplayName("detects JSON from .json extension")
        void detectJsonExtension() {
            assertThat(ConfigFormatParser.detectFormat("config.json", null)).isEqualTo("json");
        }

        @Test
        @DisplayName("detects properties from .properties extension")
        void detectPropertiesExtension() {
            assertThat(ConfigFormatParser.detectFormat("config.properties", null)).isEqualTo("properties");
        }

        @Test
        @DisplayName("falls back to content sniffing when no extension matches")
        void contentSniffing() {
            assertThat(ConfigFormatParser.detectFormat("config", "{\"key\":\"val\"}")).isEqualTo("json");
            assertThat(ConfigFormatParser.detectFormat("config", "key: value")).isEqualTo("yaml");
            assertThat(ConfigFormatParser.detectFormat("config", "key=value")).isEqualTo("properties");
        }

        @Test
        @DisplayName("defaults to yaml when format is ambiguous")
        void defaultsToYaml() {
            assertThat(ConfigFormatParser.detectFormat(null, null)).isEqualTo("yaml");
        }
    }
}
