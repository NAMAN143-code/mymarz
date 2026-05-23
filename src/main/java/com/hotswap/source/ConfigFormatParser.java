package com.hotswap.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Parses configuration content (YAML, JSON, .properties) into a
 * flattened {@code Map<String, String>} using dot-notation keys.
 *
 * <p>Format is detected from the URI file extension first, then by
 * content sniffing if no extension is present.</p>
 *
 * @since 1.0.0
 */
public class ConfigFormatParser {

    private static final Logger log = LoggerFactory.getLogger(ConfigFormatParser.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Parse config content into a flat key-value map.
     *
     * @param content raw file/response content
     * @param uri     source URI (used for format detection)
     * @return flattened dot-notation key-value map; empty map on error
     */
    public Map<String, String> parse(String content, String uri) {
        if (content == null || content.isBlank()) {
            return Collections.emptyMap();
        }
        String format = detectFormat(uri, content);
        try {
            return switch (format) {
                case "json"       -> parseJson(content);
                case "properties" -> parseProperties(content);
                default           -> parseYaml(content);
            };
        } catch (Exception e) {
            log.error("Failed to parse config from '{}' as {}: {}", uri, format, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // -------------------------------------------------------------------------
    // Format parsers
    // -------------------------------------------------------------------------

    private Map<String, String> parseYaml(String content) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(content);
        if (!(loaded instanceof Map)) {
            log.warn("YAML content did not produce a Map at root level");
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        flatten("", (Map<?, ?>) loaded, result);
        return result;
    }

    private Map<String, String> parseJson(String content) throws Exception {
        Map<String, Object> raw = JSON_MAPPER.readValue(
                content, new TypeReference<Map<String, Object>>() {});
        Map<String, String> result = new LinkedHashMap<>();
        flatten("", raw, result);
        return result;
    }

    private Map<String, String> parseProperties(String content) throws Exception {
        Properties props = new Properties();
        props.load(new StringReader(content));
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            result.put(key, props.getProperty(key));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<?, ?> map, Map<String, String> result) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty()
                    ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<?, ?>) value, result);
            } else if (value instanceof java.util.List<?> list) {
                flattenList(key, list, result);
            } else if (value != null) {
                result.put(key, String.valueOf(value));
            }
        }
    }

    private void flattenList(String prefix, java.util.List<?> list, Map<String, String> result) {
        for (int i = 0; i < list.size(); i++) {
            String key = prefix + "." + i;
            Object item = list.get(i);
            if (item instanceof Map) {
                flatten(key, (Map<?, ?>) item, result);
            } else if (item instanceof java.util.List<?> nested) {
                flattenList(key, nested, result);
            } else if (item != null) {
                result.put(key, String.valueOf(item));
            }
        }
    }

    /**
     * Detect config format from URI extension, falling back to content sniffing.
     */
    static String detectFormat(String uri, String content) {
        if (uri != null) {
            String lower = uri.toLowerCase();
            if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
            if (lower.endsWith(".json"))                           return "json";
            if (lower.endsWith(".properties"))                     return "properties";
        }
        // Content sniffing
        if (content != null) {
            String trimmed = content.trim();
            if (trimmed.startsWith("{"))              return "json";
            if (trimmed.contains(": ") || trimmed.contains(":\n")) return "yaml";
            if (trimmed.contains("="))               return "properties";
        }
        return "yaml"; // safe default
    }
}
