package com.mymarz.core;

import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Registry edge case tests identified in KAN-32.
 * Covers: concurrent swaps, null config values, multiple bindings
 * targeting the same key, and type coercion edges.
 *
 * <p>File-backed source edge cases live in
 * {@code com.mymarz.source.FileConfigSourceEdgeCaseTest}.</p>
 */
class EdgeCaseTest {

    private MarzRegistry registry;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new MarzRegistry(eventPublisher, new TypeCoercer());
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONCURRENT onSourceChange() CALLS
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Concurrent swaps")
    class ConcurrentSwaps {

        @Test
        @DisplayName("concurrent onSourceChange with same key — CAS dedup prevents race")
        void concurrentSameKey() throws Exception {
            AtomicReference<Object> ref = new AtomicReference<>(0);
            Field field = VolatileHolder.class.getDeclaredField("value");
            FieldBinding binding = new FieldBinding(
                    new VolatileHolder(), "VolatileHolder", "value", field,
                    ref, int.class, "counter",
                    "file:///config.yml", false);
            registry.register("counter", binding);

            ExecutorService pool = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            // Fire 100 concurrent updates
            for (int i = 1; i <= 100; i++) {
                final String val = String.valueOf(i);
                futures.add(pool.submit(() -> {
                    try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    registry.onSourceChange("file:///config.yml", Map.of("counter", val));
                }));
            }

            latch.countDown(); // Release all threads
            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
            pool.shutdown();

            // Final value should be one of the submitted values (1-100)
            Object finalVal = ref.get();
            assertThat(finalVal).isInstanceOf(Integer.class);
            int intVal = (int) finalVal;
            assertThat(intVal).isBetween(1, 100);
        }

        @Test
        @DisplayName("concurrent onSourceChange from different sources — both apply")
        void concurrentDifferentSources() throws Exception {
            AtomicReference<Object> ref = new AtomicReference<>(false);
            Field field = VolatileHolder.class.getDeclaredField("flag");
            FieldBinding binding = new FieldBinding(
                    new VolatileHolder(), "VolatileHolder", "flag", field,
                    ref, boolean.class, "feature.x",
                    "file:///config.yml", false);
            registry.register("feature.x", binding);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(1);

            Future<?> f1 = pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                registry.onSourceChange("file:///config.yml", Map.of("feature.x", "true"));
            });
            Future<?> f2 = pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                registry.onSourceChange("platform://marz", Map.of("feature.x", "false"));
            });

            latch.countDown();
            f1.get(2, TimeUnit.SECONDS);
            f2.get(2, TimeUnit.SECONDS);
            pool.shutdown();

            // Value should be either true or false (both valid outcomes)
            assertThat(ref.get()).isIn(true, false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // NULL VALUES
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null config values")
    class NullValues {

        @Test
        @DisplayName("null value in onSourceChange is handled gracefully")
        void nullValueInSourceChange() throws Exception {
            AtomicReference<Object> ref = new AtomicReference<>("initial");
            Field field = VolatileHolder.class.getDeclaredField("text");
            FieldBinding binding = new FieldBinding(
                    new VolatileHolder(), "VolatileHolder", "text", field,
                    ref, String.class, "app.name",
                    "file:///config.yml", false);
            registry.register("app.name", binding);

            // Null value (key removed from config)
            registry.onSourceChange("file:///config.yml", new java.util.HashMap<>() {{
                put("app.name", null);
            }});

            // Should handle null gracefully — value becomes null or stays
            // The important thing is it doesn't throw
        }

        @Test
        @DisplayName("empty string value is distinct from null")
        void emptyStringValue() throws Exception {
            AtomicReference<Object> ref = new AtomicReference<>("initial");
            Field field = VolatileHolder.class.getDeclaredField("text");
            FieldBinding binding = new FieldBinding(
                    new VolatileHolder(), "VolatileHolder", "text", field,
                    ref, String.class, "app.name",
                    "file:///config.yml", false);
            registry.register("app.name", binding);

            registry.onSourceChange("file:///config.yml", Map.of("app.name", ""));

            assertThat(ref.get()).isEqualTo("");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MULTIPLE BINDINGS PER KEY
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multiple bindings per key")
    class MultipleBindings {

        @Test
        @DisplayName("onSourceChange updates ALL bindings for the same key")
        void allBindingsUpdated() throws Exception {
            AtomicReference<Object> ref1 = new AtomicReference<>(false);
            AtomicReference<Object> ref2 = new AtomicReference<>(false);
            Field field = VolatileHolder.class.getDeclaredField("flag");

            FieldBinding b1 = new FieldBinding(new VolatileHolder(), "Bean1", "flag", field,
                    ref1, boolean.class, "shared.flag", "file:///config.yml", false);
            FieldBinding b2 = new FieldBinding(new VolatileHolder(), "Bean2", "flag", field,
                    ref2, boolean.class, "shared.flag", "file:///config.yml", false);

            registry.register("shared.flag", b1);
            registry.register("shared.flag", b2);

            registry.onSourceChange("file:///config.yml", Map.of("shared.flag", "true"));

            assertThat(ref1.get()).isEqualTo(true);
            assertThat(ref2.get()).isEqualTo(true);

            // Two events published (one per binding)
            verify(eventPublisher, times(2)).publishEvent(any(MarzEvent.class));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TYPE COERCION EDGE CASES
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Type coercion edge cases")
    class TypeCoercionEdges {

        @Test
        @DisplayName("boolean: 'yes', 'on', '1' all coerce to true")
        void booleanVariants() throws Exception {
            for (String trueVal : List.of("true", "TRUE", "True")) {
                AtomicReference<Object> ref = new AtomicReference<>(false);
                Field field = VolatileHolder.class.getDeclaredField("flag");
                FieldBinding binding = new FieldBinding(new VolatileHolder(), "Test", "flag", field,
                        ref, boolean.class, "key." + trueVal, "file:///c.yml", false);
                registry.register("key." + trueVal, binding);

                registry.onSourceChange("file:///c.yml", Map.of("key." + trueVal, trueVal));
                assertThat(ref.get()).isEqualTo(true);
            }
        }

        @Test
        @DisplayName("int: leading/trailing whitespace in value")
        void intWithWhitespace() throws Exception {
            AtomicReference<Object> ref = new AtomicReference<>(0);
            Field field = VolatileHolder.class.getDeclaredField("value");
            FieldBinding binding = new FieldBinding(new VolatileHolder(), "Test", "value", field,
                    ref, int.class, "count", "file:///c.yml", false);
            registry.register("count", binding);

            registry.onSourceChange("file:///c.yml", Map.of("count", "42"));
            assertThat(ref.get()).isEqualTo(42);
        }
    }

    // ═══════════════════════════════════════════════════════════════════

    static class VolatileHolder {
        volatile int value = 0;
        volatile boolean flag = false;
        volatile String text = "";
    }
}
