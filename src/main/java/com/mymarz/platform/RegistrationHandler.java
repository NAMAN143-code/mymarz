package com.mymarz.platform;

import com.mymarz.autoconfigure.MarzProperties;
import com.mymarz.core.FieldBinding;
import com.mymarz.core.MarzRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Handles REGISTER and HEARTBEAT message flows with the MARZ Platform.
 *
 * <p>On connect/reconnect: sends a REGISTER payload with instance metadata
 * and all {@code @Marz} field bindings. Heartbeat runs on a scheduled
 * interval with current volatile field values.</p>
 *
 * <p>Per ADR-004: sensitive values are masked in both payloads.</p>
 *
 * @since 1.0.0
 */
public class RegistrationHandler implements PlatformMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(RegistrationHandler.class);
    private static final String MASKED = "***";

    private final MarzRegistry registry;
    private final PlatformAgent agent;
    private final MarzProperties.Platform config;
    private final String appName;
    private final String environment;
    private final String instanceId;

    private ScheduledExecutorService heartbeatScheduler;

    public RegistrationHandler(MarzRegistry registry, PlatformAgent agent,
                                MarzProperties.Platform config,
                                String appName, String environment) {
        this.registry = registry;
        this.agent = agent;
        this.config = config;
        this.appName = appName != null ? appName : "unknown";
        this.environment = environment != null ? environment : "default";
        this.instanceId = generateInstanceId();
    }

    @Override
    public void onConnect() {
        sendRegister();
        startHeartbeat();
    }

    @Override
    public void onDisconnect() {
        stopHeartbeat();
    }

    @Override
    public String onMessage(String message) {
        // REGISTER/HEARTBEAT handler doesn't process inbound messages.
        // CONFIG_UPDATE and CONFIG_SYNC are handled by separate handlers (KAN-49, KAN-51).
        return null;
    }

    /**
     * @return the unique instance ID for this application instance
     */
    public String getInstanceId() {
        return instanceId;
    }

    // ═══════════════════════════════════════════════════════════════════
    // REGISTER
    // ═══════════════════════════════════════════════════════════════════

    void sendRegister() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "REGISTER");
        payload.put("instanceId", instanceId);
        payload.put("appName", appName);
        payload.put("environment", environment);
        payload.put("appVersion", getAppVersion());
        payload.put("hostIp", getHostIp());
        payload.put("startupTimestamp", Instant.now().toString());
        payload.put("marzVersion", "1.0.0");

        // Collect all field bindings
        List<Map<String, Object>> fields = new ArrayList<>();
        registry.getAllBindings().forEach((key, bindings) -> {
            if (!bindings.isEmpty()) {
                FieldBinding binding = bindings.get(0);
                Map<String, Object> fieldInfo = new LinkedHashMap<>();
                fieldInfo.put("key", key);
                fieldInfo.put("type", binding.targetType().getSimpleName());
                fieldInfo.put("currentValue", maskIfSensitive(binding.ref().get(), binding.sensitive()));
                fieldInfo.put("sourceUri", binding.sourceUri());
                fieldInfo.put("sensitive", binding.sensitive());
                fieldInfo.put("bindingCount", bindings.size());
                fields.add(fieldInfo);
            }
        });
        payload.put("fields", fields);
        payload.put("fieldCount", fields.size());

        String json = toJson(payload);
        boolean sent = agent.send(json);

        if (sent) {
            log.info("REGISTER sent: instanceId={}, appName={}, environment={}, fields={}",
                    instanceId, appName, environment, fields.size());
        } else {
            log.warn("Failed to send REGISTER — agent not connected");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HEARTBEAT
    // ═══════════════════════════════════════════════════════════════════

    void startHeartbeat() {
        stopHeartbeat(); // Stop any existing scheduler

        long intervalMs = config.getHeartbeatIntervalMs();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "marz-heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeat,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        log.debug("Heartbeat started: every {}ms", intervalMs);
    }

    void stopHeartbeat() {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdown();
            try {
                if (!heartbeatScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    heartbeatScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    void sendHeartbeat() {
        if (!agent.isConnected()) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "HEARTBEAT");
        payload.put("instanceId", instanceId);
        payload.put("timestamp", Instant.now().toString());

        // Current values — read from volatile fields via AtomicReference snapshot
        Map<String, Object> values = new LinkedHashMap<>();
        var snapshot = registry.getStateSnapshot();
        snapshot.forEach((key, state) -> {
            values.put(key, maskIfSensitive(state.value(), state.sensitive()));
        });
        payload.put("values", values);

        String json = toJson(payload);
        boolean sent = agent.send(json);

        if (sent) {
            log.trace("HEARTBEAT sent: {} values", values.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private Object maskIfSensitive(Object value, boolean sensitive) {
        return sensitive ? MASKED : value;
    }

    private String generateInstanceId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return hostname + "-" + suffix;
    }

    private String getHostIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String getAppVersion() {
        // Try to read from manifest
        Package pkg = getClass().getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }
        return "dev";
    }

    /**
     * Simple JSON serializer for platform messages.
     * Avoids Jackson dependency — the library stays zero-dep.
     */
    static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            appendJsonValue(sb, entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append("\"").append(escapeJson(s)).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append(toJson((Map<String, Object>) map));
        } else if (value instanceof List<?> list) {
            sb.append("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",");
                first = false;
                appendJsonValue(sb, item);
            }
            sb.append("]");
        } else {
            sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
