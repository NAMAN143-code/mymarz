package com.mymarz.platform;

import com.mymarz.core.FieldBinding;
import com.mymarz.core.MarzRegistry;
import com.mymarz.source.ConfigFormatParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles CONFIG_UPDATE and CONFIG_SYNC messages from the MARZ Platform.
 *
 * <p><strong>Hot path</strong> (synchronous, &lt;1ms target): HMAC validate → 
 * {@code registry.onSourceChange()} → CAS dedup → volatile {@code field.set()} →
 * MarzEvent published → CONFIG_ACK sent.</p>
 *
 * <p><strong>Cold path</strong> (async, fire-and-forget): write updated value to
 * local config file for crash resilience. WatchService self-detection: the write
 * triggers diff → zero changes → no-op (CAS dedup).</p>
 *
 * <p>Per ADR-003: HMAC validation is <em>mandatory</em>. The agent NEVER applies
 * a CONFIG_UPDATE without a valid signature.</p>
 *
 * @since 1.0.0
 */
public class ConfigUpdateHandler implements PlatformMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfigUpdateHandler.class);
    private static final String HMAC_ALGO = "HmacSHA256";

    private final MarzRegistry registry;
    private final PlatformAgent agent;
    private final String apiKey; // HMAC shared secret derived from API key
    private final String instanceId;

    private final ExecutorService coldPathExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "marz-cold-path");
        t.setDaemon(true);
        return t;
    });

    public ConfigUpdateHandler(MarzRegistry registry, PlatformAgent agent,
                                String apiKey, String instanceId) {
        this.registry = registry;
        this.agent = agent;
        this.apiKey = apiKey;
        this.instanceId = instanceId;
    }

    @Override
    public String onMessage(String message) {
        // Simple JSON field extraction (no Jackson dependency)
        String type = extractJsonString(message, "type");

        if ("CONFIG_UPDATE".equals(type)) {
            return handleConfigUpdate(message);
        } else if ("CONFIG_SYNC".equals(type)) {
            return handleConfigSync(message);
        }

        return null; // Not our message type
    }

    @Override public void onConnect() { /* Handled by RegistrationHandler */ }
    @Override public void onDisconnect() { /* No cleanup needed */ }

    // ═══════════════════════════════════════════════════════════════════
    // CONFIG_UPDATE — single key change
    // ═══════════════════════════════════════════════════════════════════

    String handleConfigUpdate(String message) {
        String changeId = extractJsonString(message, "changeId");
        String key = extractJsonString(message, "key");
        String value = extractJsonString(message, "value");
        String hmac = extractJsonString(message, "hmac");

        // ── HMAC VALIDATION (mandatory per ADR-003) ──
        if (!validateHmac(key + ":" + value, hmac)) {
            log.warn("CONFIG_UPDATE rejected: HMAC_INVALID for key '{}'", key);
            return buildNack(changeId, key, "HMAC_INVALID");
        }

        // ── HOT PATH ──
        return applyChange(changeId, key, value);
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONFIG_SYNC — batch reconciliation after reconnect
    // ═══════════════════════════════════════════════════════════════════

    String handleConfigSync(String message) {
        String hmac = extractJsonString(message, "hmac");

        // Extract the changes block
        String changesBlock = extractJsonBlock(message, "changes");
        if (changesBlock == null || changesBlock.isEmpty() || "{}".equals(changesBlock)) {
            log.info("CONFIG_SYNC: agent in sync — no deltas from platform");
            return null;
        }

        // Validate HMAC over the entire changes block
        if (!validateHmac(changesBlock, hmac)) {
            log.warn("CONFIG_SYNC rejected: HMAC_INVALID");
            return buildNack(null, null, "HMAC_INVALID");
        }

        // Parse changes and apply each
        Map<String, String> changes = parseSimpleJsonMap(changesBlock);
        List<String> acks = new ArrayList<>();
        int applied = 0;

        for (var entry : changes.entrySet()) {
            String result = applyChange(null, entry.getKey(), entry.getValue());
            if (result != null) acks.add(result);
            if (result != null && result.contains("CONFIG_ACK")) applied++;
        }

        log.info("CONFIG_SYNC: reconciled {} key(s) after reconnect ({} applied, {} failed)",
                changes.size(), applied, changes.size() - applied);

        // Cold path: batch write all synced values
        if (applied > 0) {
            coldPathExecutor.submit(() -> batchWriteToFile(changes));
        }

        // Return combined response
        return "[" + String.join(",", acks) + "]";
    }

    // ═══════════════════════════════════════════════════════════════════
    // SHARED HOT PATH
    // ═══════════════════════════════════════════════════════════════════

    private String applyChange(String changeId, String key, String value) {
        // Check key exists in registry
        List<FieldBinding> bindings = registry.getBindings(key);
        if (bindings == null || bindings.isEmpty()) {
            log.warn("CONFIG_UPDATE: KEY_NOT_FOUND '{}'", key);
            return buildNack(changeId, key, "KEY_NOT_FOUND");
        }

        // Apply via the standard change path. onSourceChange now isolates per-key
        // (KAN-98) and reports failures via its return value instead of throwing,
        // so a poison value still produces a TYPE_MISMATCH NACK to the platform.
        try {
            Set<String> failed = registry.onSourceChange("platform://marz", Map.of(key, value));
            if (failed.contains(key)) {
                log.warn("CONFIG_UPDATE: TYPE_MISMATCH for key '{}' (value '{}')", key, value);
                return buildNack(changeId, key, "TYPE_MISMATCH");
            }
        } catch (Exception e) {
            log.warn("CONFIG_UPDATE: TYPE_MISMATCH for key '{}': {}", key, e.getMessage());
            return buildNack(changeId, key, "TYPE_MISMATCH");
        }

        log.debug("CONFIG_UPDATE applied: key='{}' (changeId={})", key, changeId);

        // Cold path: async file write (single key)
        if (changeId != null) {
            String sourceUri = bindings.get(0).sourceUri();
            coldPathExecutor.submit(() -> writeToFile(sourceUri, key, value));
        }

        return buildAck(changeId, key);
    }

    // ═══════════════════════════════════════════════════════════════════
    // COLD PATH — async file persistence
    // ═══════════════════════════════════════════════════════════════════

    private void writeToFile(String sourceUri, String key, String value) {
        try {
            if (sourceUri == null || !sourceUri.startsWith("file:")) return;

            Path filePath = resolveFilePath(sourceUri);
            if (!Files.exists(filePath) || !Files.isWritable(filePath)) return;

            String content = Files.readString(filePath);
            ConfigFormatParser parser = new ConfigFormatParser();
            Map<String, String> existing = new LinkedHashMap<>(parser.parse(content, sourceUri));
            existing.put(key, value);

            // Rewrite (simplified — preserves flat structure)
            writeMap(filePath, existing);

            log.debug("Cold path: wrote key '{}' to {}", key, filePath);
        } catch (Exception e) {
            log.warn("Cold path write failed for key '{}': {}", key, e.getMessage());
        }
    }

    private void batchWriteToFile(Map<String, String> changes) {
        // Group changes by source URI from their bindings
        Map<String, Map<String, String>> bySource = new LinkedHashMap<>();
        for (var entry : changes.entrySet()) {
            List<FieldBinding> bindings = registry.getBindings(entry.getKey());
            if (bindings != null && !bindings.isEmpty()) {
                String source = bindings.get(0).sourceUri();
                bySource.computeIfAbsent(source, k -> new LinkedHashMap<>())
                        .put(entry.getKey(), entry.getValue());
            }
        }

        for (var entry : bySource.entrySet()) {
            try {
                if (!entry.getKey().startsWith("file:")) continue;
                Path filePath = resolveFilePath(entry.getKey());
                if (!Files.exists(filePath) || !Files.isWritable(filePath)) continue;

                String content = Files.readString(filePath);
                ConfigFormatParser parser = new ConfigFormatParser();
                Map<String, String> existing = new LinkedHashMap<>(parser.parse(content, entry.getKey()));
                existing.putAll(entry.getValue());
                writeMap(filePath, existing);

                log.debug("Cold path: batch wrote {} key(s) to {}", entry.getValue().size(), filePath);
            } catch (Exception e) {
                log.warn("Cold path batch write failed for '{}': {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void writeMap(Path filePath, Map<String, String> map) throws Exception {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".properties")) {
            StringBuilder sb = new StringBuilder();
            for (var entry : map.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue() != null ? entry.getValue() : "").append("\n");
            }
            Files.writeString(filePath, sb.toString());
        } else {
            // YAML: rebuild nested structure
            org.yaml.snakeyaml.DumperOptions opts = new org.yaml.snakeyaml.DumperOptions();
            opts.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml(opts);

            Map<String, Object> nested = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                setNestedValue(nested, entry.getKey(), entry.getValue());
            }
            Files.writeString(filePath, yaml.dump(nested));
        }
    }

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
        current.put(parts[parts.length - 1], value);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HMAC VALIDATION
    // ═══════════════════════════════════════════════════════════════════

    boolean validateHmac(String payload, String expectedHmac) {
        if (expectedHmac == null || expectedHmac.isEmpty()) {
            log.debug("No HMAC provided — rejecting");
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = bytesToHex(hash);
            return computed.equalsIgnoreCase(expectedHmac);
        } catch (Exception e) {
            log.error("HMAC computation failed: {}", e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ACK / NACK BUILDERS
    // ═══════════════════════════════════════════════════════════════════

    private String buildAck(String changeId, String key) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "CONFIG_ACK");
        ack.put("changeId", changeId);
        ack.put("instanceId", instanceId);
        ack.put("key", key);
        ack.put("applied", true);
        ack.put("timestamp", Instant.now().toString());
        return RegistrationHandler.toJson(ack);
    }

    private String buildNack(String changeId, String key, String reason) {
        Map<String, Object> nack = new LinkedHashMap<>();
        nack.put("type", "CONFIG_NACK");
        nack.put("changeId", changeId);
        nack.put("instanceId", instanceId);
        nack.put("key", key);
        nack.put("reason", reason);
        nack.put("applied", false);
        nack.put("timestamp", Instant.now().toString());
        return RegistrationHandler.toJson(nack);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private Path resolveFilePath(String uri) {
        String path = uri;
        for (String prefix : List.of("file:///", "file://", "file:")) {
            if (path.startsWith(prefix)) { path = path.substring(prefix.length()); break; }
        }
        return Path.of(path);
    }

    /**
     * Extract a simple string value from JSON by key.
     * Lightweight — avoids Jackson dependency in the library.
     */
    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end).replace("\\\"", "\"").replace("\\n", "\n");
    }

    /**
     * Extract a JSON object block by key (e.g., "changes":{...}).
     */
    static String extractJsonBlock(String json, String key) {
        String search = "\"" + key + "\":{";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start = json.indexOf("{", start);
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}') depth--;
            if (depth == 0) return json.substring(start, i + 1);
        }
        return null;
    }

    /**
     * Parse a simple flat JSON object {"key1":"val1","key2":"val2"}.
     */
    static Map<String, String> parseSimpleJsonMap(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        String inner = json.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);

        String[] pairs = inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().replaceAll("^\"|\"$", "");
                String v = kv[1].trim().replaceAll("^\"|\"$", "");
                if (!k.isEmpty()) result.put(k, v);
            }
        }
        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
