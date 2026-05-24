package com.mymarz.platform;

import com.mymarz.core.FieldBinding;
import com.mymarz.core.MarzRegistry;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfigUpdateHandlerTest {

    private static final String API_KEY = "test-secret-key";

    private MarzRegistry registry;
    private PlatformAgent agent;
    private ConfigUpdateHandler handler;
    private List<String> sentMessages;

    @BeforeEach
    void setUp() {
        registry = new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());

        var config = new com.mymarz.autoconfigure.MarzProperties.Platform();
        config.setApiKey(API_KEY);
        config.setEndpoint("wss://localhost:19999/agent");

        agent = spy(new PlatformAgent(config, List.of()));
        doReturn(true).when(agent).isConnected();
        doReturn(true).when(agent).send(anyString());

        handler = new ConfigUpdateHandler(registry, agent, API_KEY, "test-instance-001");
    }

    // ═══════════════════════════════════════════════════════════════════
    // HMAC VALIDATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("validateHmac: valid HMAC passes")
    void validHmac() {
        String payload = "feature.enabled:true";
        String hmac = computeHmac(payload, API_KEY);
        assertThat(handler.validateHmac(payload, hmac)).isTrue();
    }

    @Test
    @DisplayName("validateHmac: wrong HMAC rejected")
    void invalidHmac() {
        assertThat(handler.validateHmac("payload", "badhmac123")).isFalse();
    }

    @Test
    @DisplayName("validateHmac: null HMAC rejected")
    void nullHmac() {
        assertThat(handler.validateHmac("payload", null)).isFalse();
    }

    @Test
    @DisplayName("validateHmac: empty HMAC rejected")
    void emptyHmac() {
        assertThat(handler.validateHmac("payload", "")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONFIG_UPDATE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CONFIG_UPDATE: valid HMAC + known key → ACK")
    void configUpdateSuccess() throws Exception {
        registerBinding("feature.enabled", false, boolean.class);
        String hmac = computeHmac("feature.enabled:true", API_KEY);

        String msg = "{\"type\":\"CONFIG_UPDATE\",\"changeId\":\"chg-001\",\"key\":\"feature.enabled\",\"value\":\"true\",\"hmac\":\"" + hmac + "\"}";
        String response = handler.onMessage(msg);

        assertThat(response).contains("CONFIG_ACK");
        assertThat(response).contains("chg-001");
        assertThat(response).contains("\"applied\":true");
    }

    @Test
    @DisplayName("CONFIG_UPDATE: invalid HMAC → NACK(HMAC_INVALID)")
    void configUpdateInvalidHmac() throws Exception {
        registerBinding("feature.enabled", false, boolean.class);

        String msg = "{\"type\":\"CONFIG_UPDATE\",\"changeId\":\"chg-002\",\"key\":\"feature.enabled\",\"value\":\"true\",\"hmac\":\"badhmac\"}";
        String response = handler.onMessage(msg);

        assertThat(response).contains("CONFIG_NACK");
        assertThat(response).contains("HMAC_INVALID");
    }

    @Test
    @DisplayName("CONFIG_UPDATE: unknown key → NACK(KEY_NOT_FOUND)")
    void configUpdateKeyNotFound() {
        String hmac = computeHmac("nonexistent.key:value", API_KEY);

        String msg = "{\"type\":\"CONFIG_UPDATE\",\"changeId\":\"chg-003\",\"key\":\"nonexistent.key\",\"value\":\"value\",\"hmac\":\"" + hmac + "\"}";
        String response = handler.onMessage(msg);

        assertThat(response).contains("CONFIG_NACK");
        assertThat(response).contains("KEY_NOT_FOUND");
    }

    @Test
    @DisplayName("CONFIG_UPDATE: unrelated message type → null")
    void unrelatedMessageIgnored() {
        String response = handler.onMessage("{\"type\":\"HEARTBEAT\"}");
        assertThat(response).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONFIG_SYNC
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CONFIG_SYNC: empty changes → in sync")
    void configSyncEmpty() {
        String hmac = computeHmac("{}", API_KEY);
        String msg = "{\"type\":\"CONFIG_SYNC\",\"changes\":{},\"hmac\":\"" + hmac + "\"}";
        String response = handler.onMessage(msg);
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("CONFIG_SYNC: applies batch changes")
    void configSyncBatch() throws Exception {
        registerBinding("feature.a", false, boolean.class);
        registerBinding("rate.limit", 100, int.class);

        String changesBlock = "{\"feature.a\":\"true\",\"rate.limit\":\"500\"}";
        String hmac = computeHmac(changesBlock, API_KEY);

        String msg = "{\"type\":\"CONFIG_SYNC\",\"changes\":" + changesBlock + ",\"hmac\":\"" + hmac + "\"}";
        String response = handler.onMessage(msg);

        assertThat(response).contains("CONFIG_ACK");
        assertThat(response).contains("feature.a");
        assertThat(response).contains("rate.limit");
    }

    // ═══════════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extractJsonString: finds string values")
    void extractString() {
        String json = "{\"type\":\"CONFIG_UPDATE\",\"key\":\"feature.x\"}";
        assertThat(ConfigUpdateHandler.extractJsonString(json, "type")).isEqualTo("CONFIG_UPDATE");
        assertThat(ConfigUpdateHandler.extractJsonString(json, "key")).isEqualTo("feature.x");
    }

    @Test
    @DisplayName("extractJsonString: returns null for missing key")
    void extractStringMissing() {
        assertThat(ConfigUpdateHandler.extractJsonString("{\"a\":\"b\"}", "missing")).isNull();
    }

    @Test
    @DisplayName("extractJsonBlock: finds nested object")
    void extractBlock() {
        String json = "{\"type\":\"SYNC\",\"changes\":{\"a\":\"1\",\"b\":\"2\"}}";
        String block = ConfigUpdateHandler.extractJsonBlock(json, "changes");
        assertThat(block).isEqualTo("{\"a\":\"1\",\"b\":\"2\"}");
    }

    @Test
    @DisplayName("parseSimpleJsonMap: parses flat map")
    void parseMap() {
        Map<String, String> result = ConfigUpdateHandler.parseSimpleJsonMap("{\"a\":\"1\",\"b\":\"hello\"}");
        assertThat(result).containsEntry("a", "1");
        assertThat(result).containsEntry("b", "hello");
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private void registerBinding(String key, Object value, Class<?> type) throws Exception {
        Field field = TestFields.class.getDeclaredField("testField");
        FieldBinding binding = new FieldBinding(
                this, "TestBean", "testField", field,
                new AtomicReference<>(value), type, key,
                "file:///config.yml", false
        );
        registry.register(key, binding);
    }

    private static String computeHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class TestFields {
        volatile Object testField;
    }
}
