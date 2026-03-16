package com.hotswap.core;

import com.hotswap.annotation.HotSwap;
import com.hotswap.annotation.HotSwapType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HotSwapRegistryTest {

    private HotSwapRegistry registry;

    @SuppressWarnings("unused")
    private boolean testField = false;

    @BeforeEach
    void setUp() {
        registry = new HotSwapRegistry();
    }

    @Test
    void register_addsFieldToSourceGroup() throws Exception {
        HotSwapFieldHolder holder = createHolder("feature.enabled", "file:///config.yml", 5000L);
        registry.register(holder);

        assertThat(registry.getFieldCount()).isEqualTo(1);
        assertThat(registry.getSourceCount()).isEqualTo(1);
        assertThat(registry.getFieldsForSource("file:///config.yml")).containsExactly(holder);
        assertThat(registry.getFieldByKey("feature.enabled")).isSameAs(holder);
    }

    @Test
    void register_multipleFieldsSameSource() throws Exception {
        HotSwapFieldHolder h1 = createHolder("feature.a", "file:///config.yml", 5000L);
        HotSwapFieldHolder h2 = createHolder("feature.b", "file:///config.yml", 3000L);
        registry.register(h1);
        registry.register(h2);

        assertThat(registry.getFieldCount()).isEqualTo(2);
        assertThat(registry.getSourceCount()).isEqualTo(1);
        assertThat(registry.getFieldsForSource("file:///config.yml")).containsExactly(h1, h2);
    }

    @Test
    void register_multipleFieldsDifferentSources() throws Exception {
        HotSwapFieldHolder h1 = createHolder("feature.a", "file:///a.yml", 5000L);
        HotSwapFieldHolder h2 = createHolder("feature.b", "file:///b.yml", 3000L);
        registry.register(h1);
        registry.register(h2);

        assertThat(registry.getFieldCount()).isEqualTo(2);
        assertThat(registry.getSourceCount()).isEqualTo(2);
    }

    @Test
    void getMinPollInterval_returnsShortestInterval() throws Exception {
        registry.register(createHolder("a", "file:///config.yml", 10000L));
        registry.register(createHolder("b", "file:///config.yml", 3000L));
        registry.register(createHolder("c", "file:///config.yml", 7000L));

        assertThat(registry.getMinPollInterval("file:///config.yml")).isEqualTo(3000L);
    }

    @Test
    void getMinPollInterval_ignoresPushOnly() throws Exception {
        registry.register(createHolder("a", "file:///config.yml", -1L));
        registry.register(createHolder("b", "file:///config.yml", 5000L));

        assertThat(registry.getMinPollInterval("file:///config.yml")).isEqualTo(5000L);
    }

    @Test
    void getMinPollInterval_unknownSource_returnsDefault() {
        assertThat(registry.getMinPollInterval("unknown")).isEqualTo(5000L);
    }

    @Test
    void registerSource_andRetrieve() {
        ConfigSource mockSource = mock(ConfigSource.class);
        registry.registerSource("file:///config.yml", mockSource);

        assertThat(registry.getConfigSource("file:///config.yml")).isSameAs(mockSource);
    }

    @Test
    void clear_removesEverything() throws Exception {
        registry.register(createHolder("a", "file:///config.yml", 5000L));
        registry.registerSource("file:///config.yml", mock(ConfigSource.class));

        registry.clear();

        assertThat(registry.getFieldCount()).isZero();
        assertThat(registry.getSourceCount()).isZero();
        assertThat(registry.getConfigSource("file:///config.yml")).isNull();
    }

    @Test
    void getFieldsForSource_unknownSource_returnsEmptyList() {
        assertThat(registry.getFieldsForSource("nonexistent")).isEmpty();
    }

    private HotSwapFieldHolder createHolder(String key, String source, long pollInterval) throws Exception {
        Field field = HotSwapRegistryTest.class.getDeclaredField("testField");
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
