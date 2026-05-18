package com.hotswap.core;

import com.hotswap.annotation.HotSwap;
import com.hotswap.annotation.HotSwapType;
import com.hotswap.type.TypeCoercer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfigSourcePollerTest {

    private HotSwapRegistry registry;
    private TypeCoercer typeCoercer;
    private ApplicationEventPublisher eventPublisher;
    private ConfigSourcePoller poller;

    @SuppressWarnings("unused")
    private boolean testField = false;

    @BeforeEach
    void setUp() {
        registry = new HotSwapRegistry();
        typeCoercer = new TypeCoercer();
        eventPublisher = mock(ApplicationEventPublisher.class);
        poller = new ConfigSourcePoller(registry, typeCoercer, eventPublisher, 2);
    }

    @AfterEach
    void tearDown() {
        poller.shutdown();
    }

    @Test
    void pollSource_updatesFieldWhenValueChanges() throws Exception {
        // Set up a mock config source
        ConfigSource source = mock(ConfigSource.class);
        when(source.isAvailable()).thenReturn(true);
        when(source.resolve("feature.enabled")).thenReturn("true");

        // Register source and field
        String sourceUri = "file:///config.yml";
        registry.registerSource(sourceUri, source);
        HotSwapFieldHolder holder = createHolder("feature.enabled", sourceUri, 5000L);
        registry.register(holder);

        // Poll
        poller.pollSource(sourceUri);

        // Verify field was updated
        assertThat(holder.getCurrentValue()).isEqualTo(true);
        verify(eventPublisher).publishEvent(any(HotSwapEvent.class));
    }

    @Test
    void pollSource_doesNotFireEvent_whenValueUnchanged() throws Exception {
        ConfigSource source = mock(ConfigSource.class);
        when(source.isAvailable()).thenReturn(true);
        when(source.resolve("feature.enabled")).thenReturn("true");

        String sourceUri = "file:///config.yml";
        registry.registerSource(sourceUri, source);
        HotSwapFieldHolder holder = createHolder("feature.enabled", sourceUri, 5000L);
        registry.register(holder);

        // Poll twice — second time value hasn't changed
        poller.pollSource(sourceUri);
        reset(eventPublisher);
        poller.pollSource(sourceUri);

        // No event on second poll
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void pollSource_incrementsFailureCount_whenSourceUnavailable() throws Exception {
        ConfigSource source = mock(ConfigSource.class);
        when(source.isAvailable()).thenReturn(false);

        String sourceUri = "file:///config.yml";
        registry.registerSource(sourceUri, source);
        registry.register(createHolder("feature.a", sourceUri, 5000L));

        poller.pollSource(sourceUri);

        assertThat(poller.getFailureCount(sourceUri)).isEqualTo(1);
    }

    @Test
    void pollSource_resetsFailureCount_onSuccess() throws Exception {
        ConfigSource source = mock(ConfigSource.class);

        // First: fail
        when(source.isAvailable()).thenReturn(false);
        String sourceUri = "file:///config.yml";
        registry.registerSource(sourceUri, source);
        registry.register(createHolder("feature.a", sourceUri, 5000L));

        poller.pollSource(sourceUri);
        assertThat(poller.getFailureCount(sourceUri)).isEqualTo(1);

        // Then: succeed
        when(source.isAvailable()).thenReturn(true);
        when(source.resolve("feature.a")).thenReturn("true");

        poller.pollSource(sourceUri);
        assertThat(poller.getFailureCount(sourceUri)).isZero();
    }

    @Test
    void calculateBackoff_exponential() {
        assertThat(poller.calculateBackoff(1)).isEqualTo(1000L);
        assertThat(poller.calculateBackoff(5)).isEqualTo(16000L);
        assertThat(poller.calculateBackoff(20)).isLessThanOrEqualTo(60000L); // Capped
    }

    @Test
    void start_andShutdown() throws Exception {
        ConfigSource source = mock(ConfigSource.class);
        when(source.isAvailable()).thenReturn(true);

        registry.registerSource("file:///config.yml", source);
        registry.register(createHolder("a", "file:///config.yml", 5000L));

        poller.start();
        assertThat(poller.isRunning()).isTrue();

        poller.shutdown();
        assertThat(poller.isRunning()).isFalse();
    }

    @Test
    void start_withNoFields_doesNotStartScheduler() {
        poller.start();
        assertThat(poller.isRunning()).isFalse();
    }

    private HotSwapFieldHolder createHolder(String key, String source, long pollInterval) throws Exception {
        Field field = ConfigSourcePollerTest.class.getDeclaredField("testField");
        HotSwap annotation = mock(HotSwap.class);
        when(annotation.key()).thenReturn(key);
        when(annotation.source()).thenReturn(source);
        when(annotation.pollInterval()).thenReturn(pollInterval);
        when(annotation.defaultValue()).thenReturn("");
        when(annotation.type()).thenReturn(HotSwapType.INFERRED);
        when(annotation.description()).thenReturn("");
        when(annotation.requiresApproval()).thenReturn(false);

        return new HotSwapFieldHolder(this, field, annotation, false);
    }
}
