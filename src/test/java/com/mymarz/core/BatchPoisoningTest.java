package com.mymarz.core;

import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAN-98: one un-coercible value in a batch must never permanently drop the rest.
 * {@link MarzRegistry#onSourceChange} isolates each key/binding, reports failures via
 * its return value (so the source can retry), publishes a failure {@link MarzEvent},
 * and defines removed-key ({@code null}) semantics without writing null to primitives.
 */
class BatchPoisoningTest {

    static class Holder {
        volatile int count;
        volatile boolean flag;
        volatile String name = "init";
    }

    private MarzRegistry registry;
    private List<MarzEvent> events;
    private Holder holder;

    @BeforeEach
    void setUp() {
        events = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher publisher = event -> events.add((MarzEvent) event);
        registry = new MarzRegistry(publisher, new TypeCoercer());
        holder = new Holder();
    }

    private void bind(String fieldName, String key, String defaultValue) throws Exception {
        Field f = Holder.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        AtomicReference<Object> ref = new AtomicReference<>(f.get(holder));
        registry.register(key, new FieldBinding(
                holder, "Holder", fieldName, f, ref, f.getType(), key, "file:///c.yml", false, defaultValue));
    }

    @Test
    @DisplayName("one poison value does not drop the rest of the batch; failed key is reported")
    void poisonValue_isolatedFromBatch() throws Exception {
        bind("count", "rate.limit", "");
        bind("flag", "feature.x", "");
        bind("name", "app.name", "");

        // Poison key FIRST — the original bug dropped every key after it.
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("rate.limit", "abc"); // un-coercible int
        batch.put("feature.x", "true");
        batch.put("app.name", "hello");

        Set<String> failed = registry.onSourceChange("src", batch);

        // Only the poison key failed; the other two applied.
        assertThat(failed).containsExactly("rate.limit");
        assertThat(holder.count).isZero();      // unchanged
        assertThat(holder.flag).isTrue();       // applied despite the poison ahead of it
        assertThat(holder.name).isEqualTo("hello");

        // A failure event was published for the poison key.
        assertThat(events).anyMatch(e -> e.isFailure() && e.getKey().equals("rate.limit"));
        // The two good keys produced normal (non-failure) change events.
        assertThat(events).filteredOn(e -> !e.isFailure())
                .extracting(MarzEvent::getKey)
                .containsExactlyInAnyOrder("feature.x", "app.name");
    }

    @Test
    @DisplayName("failed key clears and applies once a valid value arrives on the next cycle")
    void failedKey_retriesSuccessfully() throws Exception {
        bind("count", "rate.limit", "");

        assertThat(registry.onSourceChange("src", Map.of("rate.limit", "abc"))).containsExactly("rate.limit");
        assertThat(holder.count).isZero();

        Set<String> failed = registry.onSourceChange("src", Map.of("rate.limit", "42"));
        assertThat(failed).isEmpty();
        assertThat(holder.count).isEqualTo(42);
    }

    @Test
    @DisplayName("removed key (null) reverts to defaultValue when one is declared")
    void removedKey_revertsToDefault() throws Exception {
        bind("count", "rate.limit", "100");

        registry.onSourceChange("src", Map.of("rate.limit", "50"));
        assertThat(holder.count).isEqualTo(50);

        Map<String, String> removed = new HashMap<>();
        removed.put("rate.limit", null);
        Set<String> failed = registry.onSourceChange("src", removed);

        assertThat(failed).isEmpty();
        assertThat(holder.count).isEqualTo(100); // reverted to declared default
    }

    @Test
    @DisplayName("removed key (null) with no default retains the last value and never writes null to a primitive")
    void removedKey_noDefault_retainsLastValue() throws Exception {
        bind("flag", "feature.x", ""); // no defaultValue

        registry.onSourceChange("src", Map.of("feature.x", "true"));
        assertThat(holder.flag).isTrue();

        Map<String, String> removed = new HashMap<>();
        removed.put("feature.x", null);
        Set<String> failed = registry.onSourceChange("src", removed);

        assertThat(failed).isEmpty();    // null is not a failure
        assertThat(holder.flag).isTrue(); // retained — no IllegalArgumentException writing null to boolean
    }

    @Test
    @DisplayName("a clean batch reports no failures")
    void cleanBatch_reportsNoFailures() throws Exception {
        bind("count", "rate.limit", "");
        bind("name", "app.name", "");

        Set<String> failed = registry.onSourceChange("src", Map.of("rate.limit", "7", "app.name", "ok"));

        assertThat(failed).isEmpty();
        assertThat(holder.count).isEqualTo(7);
        assertThat(holder.name).isEqualTo("ok");
    }

    @Test
    @DisplayName("a key with two bindings where one coerces and the other fails: the good binding applies, the bad one is isolated, the key is reported failed")
    void multiBindingKey_partialFailureIsolated() throws Exception {
        // Same key bound to a boolean AND an int field. The value "true" coerces for the
        // boolean but fails for the int — exercising per-binding isolation WITHIN one key.
        bind("flag", "shared", "");   // boolean
        bind("count", "shared", "");  // int

        Set<String> failed = registry.onSourceChange("src", Map.of("shared", "true"));

        assertThat(failed).containsExactly("shared");
        assertThat(holder.flag).isTrue();   // good binding applied
        assertThat(holder.count).isZero();  // bad binding isolated — retained old value

        // The same key produced BOTH a change event (boolean) and a failure event (int).
        assertThat(events).anyMatch(e -> !e.isFailure() && e.getKey().equals("shared"));
        assertThat(events).anyMatch(e -> e.isFailure() && e.getKey().equals("shared"));
    }
}
