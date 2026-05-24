package com.mymarz.core;

import com.mymarz.source.ConfigFormatParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Self-registration: writes missing {@code @Marz} keys to their config files at startup.
 *
 * <p>When {@link MarzBeanPostProcessor} finds a field whose key does not exist
 * in the config source (resolved via {@code defaultValue} instead), that key is
 * recorded as "missing." After all beans are post-processed, this component
 * groups missing keys by source URI and appends them to the config files.</p>
 *
 * <p>This makes config files self-documenting — every {@code @Marz} field the
 * application uses is visible in one file without reading source code.</p>
 *
 * @since 1.0.0
 */
public class SelfRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SelfRegistrar.class);

    private final Queue<MissingKey> missingKeys = new ConcurrentLinkedQueue<>();

    /**
     * Record a key that was not found in the config source.
     */
    public void recordMissing(String key, String defaultValue, String sourceUri) {
        missingKeys.add(new MissingKey(key, defaultValue, sourceUri));
    }

    /**
     * Write all missing keys to their respective config files.
     * Called by SmartLifecycle after BPP scanning completes.
     */
    public void writeAll() {
        if (missingKeys.isEmpty()) {
            log.debug("Self-registration: all @Marz keys present in config files.");
            return;
        }

        // Group by source URI
        Map<String, List<MissingKey>> bySource = missingKeys.stream()
                .collect(Collectors.groupingBy(MissingKey::sourceUri));

        for (var entry : bySource.entrySet()) {
            String sourceUri = entry.getKey();
            List<MissingKey> keys = entry.getValue();

            try {
                writeToSource(sourceUri, keys);
            } catch (Exception e) {
                log.warn("Self-registration: failed to write {} missing key(s) to '{}': {}",
                        keys.size(), sourceUri, e.getMessage());
            }
        }
    }

    private void writeToSource(String sourceUri, List<MissingKey> keys) throws IOException {
        String scheme = extractScheme(sourceUri);

        switch (scheme) {
            case "file" -> writeToFile(sourceUri, keys);
            case "classpath" -> {
                if (isClasspathInJar(sourceUri)) {
                    log.info("Self-registration: skipping classpath source '{}' (inside JAR, read-only)", sourceUri);
                } else {
                    writeToFile(sourceUri, keys);
                }
            }
            case "http", "https" -> log.debug("Self-registration: skipping HTTP source '{}' (not a file)", sourceUri);
            case "platform" -> log.debug("Self-registration: skipping platform source '{}'", sourceUri);
            default -> log.debug("Self-registration: skipping unsupported source '{}'", sourceUri);
        }
    }

    private void writeToFile(String sourceUri, List<MissingKey> keys) throws IOException {
        Path filePath = resolveFilePath(sourceUri);

        // Check if writable
        if (Files.exists(filePath) && !Files.isWritable(filePath)) {
            log.warn("Self-registration: config file '{}' is read-only. Skipping {} missing key(s).",
                    filePath, keys.size());
            return;
        }

        // Determine format from file extension
        String fileName = filePath.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            appendYaml(filePath, keys);
        } else if (fileName.endsWith(".properties")) {
            appendProperties(filePath, keys);
        } else if (fileName.endsWith(".json")) {
            log.info("Self-registration: JSON file '{}' — {} missing key(s) not auto-appended. " +
                    "JSON structure preservation requires manual configuration.", filePath, keys.size());
        } else {
            // Default to properties format
            appendProperties(filePath, keys);
        }
    }

    private void appendYaml(Path filePath, List<MissingKey> keys) throws IOException {
        // Read existing YAML content (if file exists)
        Map<String, Object> existing;
        if (Files.exists(filePath)) {
            String content = Files.readString(filePath);
            Yaml yaml = new Yaml();
            existing = yaml.load(content);
            if (existing == null) existing = new LinkedHashMap<>();
        } else {
            existing = new LinkedHashMap<>();
            Files.createDirectories(filePath.getParent());
        }

        // Add missing keys (only if truly missing)
        int added = 0;
        ConfigFormatParser parser = new ConfigFormatParser();
        Map<String, String> flatExisting = parser.parse(
                Files.exists(filePath) ? Files.readString(filePath) : "", filePath.toString());

        for (MissingKey mk : keys) {
            if (!flatExisting.containsKey(mk.key())) {
                setNestedValue(existing, mk.key(), mk.defaultValue());
                added++;
            }
        }

        if (added == 0) {
            log.debug("Self-registration: all keys already present in '{}'", filePath);
            return;
        }

        // Write back
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        Yaml yaml = new Yaml(opts);
        Files.writeString(filePath, yaml.dump(existing));

        log.info("Self-registration: wrote {} missing key(s) to '{}'", added, filePath);
    }

    private void appendProperties(Path filePath, List<MissingKey> keys) throws IOException {
        // Read existing properties
        Set<String> existingKeys = new HashSet<>();
        if (Files.exists(filePath)) {
            Properties props = new Properties();
            try (var reader = Files.newBufferedReader(filePath)) {
                props.load(reader);
            }
            existingKeys.addAll(props.stringPropertyNames());
        } else {
            Files.createDirectories(filePath.getParent());
        }

        // Append missing keys
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (MissingKey mk : keys) {
            if (!existingKeys.contains(mk.key())) {
                sb.append(mk.key()).append("=").append(mk.defaultValue() != null ? mk.defaultValue() : "").append("\n");
                added++;
            }
        }

        if (added == 0) {
            log.debug("Self-registration: all keys already present in '{}'", filePath);
            return;
        }

        // Append separator + new keys
        if (Files.exists(filePath)) {
            String existing = Files.readString(filePath);
            if (!existing.endsWith("\n")) sb.insert(0, "\n");
            sb.insert(0, "\n# Self-registered @Marz defaults\n");
            Files.writeString(filePath, existing + sb);
        } else {
            sb.insert(0, "# Self-registered @Marz defaults\n");
            Files.writeString(filePath, sb.toString());
        }

        log.info("Self-registration: wrote {} missing key(s) to '{}'", added, filePath);
    }

    /**
     * Set a nested value in a YAML map using dot-notation key.
     * e.g., "feature.checkout.enabled" → {feature: {checkout: {enabled: value}}}
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String dotKey, String value) {
        String[] parts = dotKey.split("\\.");
        Map<String, Object> current = map;

        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.get(parts[i]);
            if (child instanceof Map) {
                current = (Map<String, Object>) child;
            } else {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(parts[i], newMap);
                current = newMap;
            }
        }

        // Only set if not already present
        current.putIfAbsent(parts[parts.length - 1], value);
    }

    private boolean isClasspathInJar(String sourceUri) {
        String resourcePath = sourceUri.replace("classpath:", "").replaceAll("^/+", "");
        var url = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
        return url != null && !"file".equals(url.getProtocol());
    }

    private Path resolveFilePath(String uri) {
        String path = uri;
        for (String prefix : List.of("file:///", "file://", "file:", "classpath:")) {
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
                break;
            }
        }
        // For classpath, resolve via ClassLoader
        if (uri.startsWith("classpath:")) {
            var url = Thread.currentThread().getContextClassLoader().getResource(path.replaceAll("^/+", ""));
            if (url != null && "file".equals(url.getProtocol())) {
                try { return Path.of(url.toURI()); }
                catch (Exception e) { /* fall through */ }
            }
        }
        return Path.of(path);
    }

    private String extractScheme(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        int colon = uri.indexOf(':');
        return colon <= 0 ? "" : uri.substring(0, colon).toLowerCase();
    }

    /**
     * Record of a key that was missing from the config source.
     */
    record MissingKey(String key, String defaultValue, String sourceUri) {}
}
