package com.mymarz.platform;

import com.mymarz.autoconfigure.MarzProperties;
import com.mymarz.core.FieldBinding;
import com.mymarz.core.MarzRegistry;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RegistrationHandlerTest {

    private MarzRegistry registry;
    private PlatformAgent agent;
    private RegistrationHandler handler;
    private List<String> sentMessages;

    @BeforeEach
    void setUp() {
        registry = new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());

        // Capture messages sent via agent
        sentMessages = new ArrayList<>();
        MarzProperties.Platform config = new MarzProperties.Platform();
        config.setApiKey("test-key");
        config.setEndpoint("wss://localhost:19999/agent");
        config.setHeartbeatIntervalMs(30_000L);

        agent = spy(new PlatformAgent(config, List.of()));
        doAnswer(inv -> {
            sentMessages.add(inv.getArgument(0));
            return true;
        }).when(agent).send(anyString());
        doReturn(true).when(agent).isConnected();

        handler = new RegistrationHandler(registry, agent, config, "test-app", "production");
    }

    @AfterEach
    void tearDown() {
        handler.stopHeartbeat();
    }

    @Test
    @DisplayName("instanceId is hostname + random suffix")
    void instanceIdFormat() {
        assertThat(handler.getInstanceId()).matches(".+-[a-f0-9]{8}");
    }

    @Test
    @DisplayName("REGISTER payload contains instance metadata")
    void registerPayloadMetadata() {
        handler.sendRegister();

        assertThat(sentMessages).hasSize(1);
        String json = sentMessages.get(0);
        assertThat(json).contains("\"type\":\"REGISTER\"");
        assertThat(json).contains("\"appName\":\"test-app\"");
        assertThat(json).contains("\"environment\":\"production\"");
        assertThat(json).contains("\"instanceId\":");
        assertThat(json).contains("\"hostIp\":");
        assertThat(json).contains("\"startupTimestamp\":");
    }

    @Test
    @DisplayName("REGISTER payload includes all field bindings")
    void registerPayloadFields() throws NoSuchFieldException {
        registerTestBinding("feature.enabled", false, boolean.class, false);
        registerTestBinding("rate.limit", 100, int.class, false);

        handler.sendRegister();

        String json = sentMessages.get(0);
        assertThat(json).contains("\"fieldCount\":2");
        assertThat(json).contains("feature.enabled");
        assertThat(json).contains("rate.limit");
    }

    @Test
    @DisplayName("REGISTER masks sensitive values")
    void registerMasksSensitiveValues() throws NoSuchFieldException {
        registerTestBinding("secrets.api-key", "sk-secret-123", String.class, true);

        handler.sendRegister();

        String json = sentMessages.get(0);
        assertThat(json).contains("***");
        assertThat(json).doesNotContain("sk-secret-123");
    }

    @Test
    @DisplayName("HEARTBEAT payload includes current values")
    void heartbeatPayload() throws NoSuchFieldException {
        registerTestBinding("feature.enabled", true, boolean.class, false);

        handler.sendHeartbeat();

        assertThat(sentMessages).hasSize(1);
        String json = sentMessages.get(0);
        assertThat(json).contains("\"type\":\"HEARTBEAT\"");
        assertThat(json).contains("\"instanceId\":");
        assertThat(json).contains("\"timestamp\":");
    }

    @Test
    @DisplayName("HEARTBEAT masks sensitive values")
    void heartbeatMasksSensitive() throws NoSuchFieldException {
        registerTestBinding("secrets.key", "top-secret", String.class, true);

        handler.sendHeartbeat();

        String json = sentMessages.get(0);
        assertThat(json).contains("***");
        assertThat(json).doesNotContain("top-secret");
    }

    @Test
    @DisplayName("onMessage returns null (no inbound processing)")
    void onMessageReturnsNull() {
        assertThat(handler.onMessage("{\"type\":\"SOMETHING\"}")).isNull();
    }

    @Test
    @DisplayName("toJson handles nested maps and lists")
    void jsonSerialization() {
        Map<String, Object> payload = Map.of(
                "type", "TEST",
                "count", 42,
                "flag", true
        );
        String json = RegistrationHandler.toJson(new java.util.LinkedHashMap<>(payload));
        assertThat(json).contains("\"type\":\"TEST\"");
        assertThat(json).contains("\"count\":42");
        assertThat(json).contains("\"flag\":true");
    }

    @Test
    @DisplayName("toJson escapes special characters")
    void jsonEscaping() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("message", "line1\nline2\ttab\"quote");
        String json = RegistrationHandler.toJson(payload);
        assertThat(json).contains("\\n");
        assertThat(json).contains("\\t");
        assertThat(json).contains("\\\"");
    }

    // ═══════════════════════════════════════════════════════════════════

    private void registerTestBinding(String key, Object value, Class<?> type, boolean sensitive) throws NoSuchFieldException {
        // Use a real volatile field for the binding
        Field field = TestFieldHolder.class.getDeclaredField("testField");
        FieldBinding binding = new FieldBinding(
                this, "TestBean", "testField", field,
                new AtomicReference<>(value), type, key,
                "file:///config.yml", sensitive
        );
        registry.register(key, binding);
    }

    static class TestFieldHolder {
        volatile Object testField;
    }
}
