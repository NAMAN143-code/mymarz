package com.mymarz.core;

import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Additional {@link MarzRegistry} coverage beyond {@link MarzRegistryTest}
 * and {@link EdgeCaseTest}.
 *
 * <p>Focuses on read-only views, snapshot semantics, source/key mappings,
 * empty registry behavior, and unmodifiable contracts.</p>
 */
class MarzRegistryExpandedTest {

    private MarzRegistry registry;
    private ApplicationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        registry = new MarzRegistry(publisher, new TypeCoercer());
    }

    // ═══════════════════════════════════════════════════════════════════
    // EMPTY REGISTRY
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Empty registry")
    class EmptyRegistry {

        @Test
        @DisplayName("getRegisteredKeyCount is zero")
        void keyCountZero() {
            assertThat(registry.getRegisteredKeyCount()).isZero();
        }

        @Test
        @DisplayName("getTotalBindingCount is zero")
        void bindingCountZero() {
            assertThat(registry.getTotalBindingCount()).isZero();
        }

        @Test
        @DisplayName("getBindings(any) returns empty list")
        void getBindingsEmpty() {
            assertThat(registry.getBindings("anything")).isEmpty();
        }

        @Test
        @DisplayName("getKeysForSource(any) returns empty list")
        void getKeysForSourceEmpty() {
            assertThat(registry.getKeysForSource("any-source")).isEmpty();
        }

        @Test
        @DisplayName("getStateSnapshot is empty")
        void snapshotEmpty() {
            assertThat(registry.getStateSnapshot()).isEmpty();
        }

        @Test
        @DisplayName("getAllBindings is empty and unmodifiable")
        void allBindingsEmpty() {
            Map<String, List<FieldBinding>> all = registry.getAllBindings();
            assertThat(all).isEmpty();
            assertThatThrownBy(() -> all.put("x", List.of()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("onSourceChange on empty registry is a no-op (no events)")
        void onSourceChangeNoop() {
            registry.onSourceChange("any", Map.of("a", "1", "b", "2"));
            verify(publisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("onSourceChange with empty map is a no-op")
        void onSourceChangeEmptyMap() {
            registry.onSourceChange("any", Collections.emptyMap());
            verify(publisher, never()).publishEvent(any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // KEYS-PER-SOURCE INDEX
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Source-to-keys index")
    class SourceIndex {

        @Test
        @DisplayName("registering bindings from the same source groups their keys")
        void groupsKeysBySource() {
            registry.register("a", binding("a", "src1"));
            registry.register("b", binding("b", "src1"));
            registry.register("c", binding("c", "src2"));

            assertThat(registry.getKeysForSource("src1")).containsExactlyInAnyOrder("a", "b");
            assertThat(registry.getKeysForSource("src2")).containsExactly("c");
        }

        @Test
        @DisplayName("registering the same key twice from one source lists it twice")
        void sameKeyTwiceFromSameSource() {
            registry.register("k", binding("k", "src"));
            registry.register("k", binding("k", "src"));
            assertThat(registry.getKeysForSource("src")).hasSize(2);
        }

        @Test
        @DisplayName("unknown source returns empty list (never null)")
        void unknownSourceEmpty() {
            registry.register("a", binding("a", "src"));
            assertThat(registry.getKeysForSource("nope")).isNotNull().isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SNAPSHOTS
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("State snapshot")
    class Snapshot {

        @Test
        @DisplayName("snapshot reports sensitive flag from the first binding")
        void sensitiveFlagInSnapshot() {
            FieldBinding sensitive = new FieldBinding(
                    new Object(), "Bean", "secret", null,
                    new AtomicReference<>("hidden"), String.class,
                    "api.secret", "src", true);
            registry.register("api.secret", sensitive);

            Map<String, MarzRegistry.FieldStateSnapshot> snapshot = registry.getStateSnapshot();
            MarzRegistry.FieldStateSnapshot s = snapshot.get("api.secret");

            assertThat(s.sensitive()).isTrue();
            assertThat(s.value()).isEqualTo("hidden");
            assertThat(s.bindingCount()).isEqualTo(1);
            assertThat(s.type()).isEqualTo("String");
        }

        @Test
        @DisplayName("snapshot bindingCount reflects how many bindings exist for the key")
        void bindingCountInSnapshot() {
            registry.register("k", binding("k", boolean.class, new AtomicReference<>(false)));
            registry.register("k", binding("k", boolean.class, new AtomicReference<>(false)));
            registry.register("k", binding("k", boolean.class, new AtomicReference<>(false)));

            MarzRegistry.FieldStateSnapshot s = registry.getStateSnapshot().get("k");
            assertThat(s.bindingCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("snapshot type uses the simple class name")
        void typeInSnapshot() {
            registry.register("i", binding("i", int.class, new AtomicReference<>(0)));
            registry.register("l", binding("l", long.class, new AtomicReference<>(0L)));
            registry.register("d", binding("d", double.class, new AtomicReference<>(0.0)));
            registry.register("s", binding("s", String.class, new AtomicReference<>("")));

            Map<String, MarzRegistry.FieldStateSnapshot> snap = registry.getStateSnapshot();
            assertThat(snap.get("i").type()).isEqualTo("int");
            assertThat(snap.get("l").type()).isEqualTo("long");
            assertThat(snap.get("d").type()).isEqualTo("double");
            assertThat(snap.get("s").type()).isEqualTo("String");
        }

        @Test
        @DisplayName("snapshot is a copy — mutating it does not affect registry state")
        void snapshotIsCopy() {
            registry.register("k", binding("k", String.class, new AtomicReference<>("v")));

            Map<String, MarzRegistry.FieldStateSnapshot> snap = registry.getStateSnapshot();
            snap.put("rogue", new MarzRegistry.FieldStateSnapshot("x", false, 1, "String"));

            assertThat(registry.getStateSnapshot()).doesNotContainKey("rogue");
        }

        @Test
        @DisplayName("FieldStateSnapshot record exposes all four components")
        void recordAccessors() {
            MarzRegistry.FieldStateSnapshot s =
                    new MarzRegistry.FieldStateSnapshot(42, true, 3, "int");

            assertThat(s.value()).isEqualTo(42);
            assertThat(s.sensitive()).isTrue();
            assertThat(s.bindingCount()).isEqualTo(3);
            assertThat(s.type()).isEqualTo("int");
        }

        @Test
        @DisplayName("FieldStateSnapshot equals and hashCode work by value (Java record)")
        void recordEqualsHashCode() {
            MarzRegistry.FieldStateSnapshot a =
                    new MarzRegistry.FieldStateSnapshot(1, false, 1, "int");
            MarzRegistry.FieldStateSnapshot b =
                    new MarzRegistry.FieldStateSnapshot(1, false, 1, "int");
            MarzRegistry.FieldStateSnapshot c =
                    new MarzRegistry.FieldStateSnapshot(2, false, 1, "int");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ALL-BINDINGS VIEW
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getAllBindings returns an unmodifiable view of the index")
    void getAllBindingsIsUnmodifiable() {
        registry.register("a", binding("a", "src"));
        Map<String, List<FieldBinding>> all = registry.getAllBindings();

        assertThat(all).containsKey("a");
        assertThatThrownBy(() -> all.remove("a"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> all.put("b", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("getAllBindings still reflects new registrations after retrieval")
    void getAllBindingsIsLiveView() {
        Map<String, List<FieldBinding>> view = registry.getAllBindings();
        assertThat(view).isEmpty();

        registry.register("a", binding("a", "src"));

        // Collections.unmodifiableMap wraps the underlying ConcurrentHashMap — changes show through
        assertThat(view).containsKey("a");
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLEAR
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("clear empties both indexes")
    void clearEmptiesBothIndexes() {
        registry.register("k1", binding("k1", "src1"));
        registry.register("k2", binding("k2", "src1"));
        registry.register("k3", binding("k3", "src2"));

        registry.clear();

        assertThat(registry.getRegisteredKeyCount()).isZero();
        assertThat(registry.getTotalBindingCount()).isZero();
        assertThat(registry.getKeysForSource("src1")).isEmpty();
        assertThat(registry.getKeysForSource("src2")).isEmpty();
        assertThat(registry.getStateSnapshot()).isEmpty();
    }

    @Test
    @DisplayName("clear is idempotent")
    void clearIdempotent() {
        registry.clear();
        registry.clear();
        // No exception
        assertThat(registry.getRegisteredKeyCount()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════════
    // SOURCE-CHANGE EVENT METADATA
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("event source is the bean, not the registry or change source")
    void eventSourceIsBean() {
        Object bean = new Object();
        AtomicReference<Object> ref = new AtomicReference<>(false);
        FieldBinding b = new FieldBinding(
                bean, "Bean", "flag", null, ref, boolean.class,
                "k", "src", false);
        registry.register("k", b);

        registry.onSourceChange("src", Map.of("k", "true"));

        org.mockito.ArgumentCaptor<MarzEvent> captor =
                org.mockito.ArgumentCaptor.forClass(MarzEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getSource()).isSameAs(bean);
    }

    @Test
    @DisplayName("event sensitive flag mirrors the binding's sensitive flag")
    void eventSensitiveMirrored() {
        AtomicReference<Object> ref = new AtomicReference<>("old");
        FieldBinding b = new FieldBinding(
                new Object(), "Bean", "secret", null, ref, String.class,
                "api.key", "src", true);
        registry.register("api.key", b);

        registry.onSourceChange("src", Map.of("api.key", "new"));

        org.mockito.ArgumentCaptor<MarzEvent> captor =
                org.mockito.ArgumentCaptor.forClass(MarzEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().isSensitive()).isTrue();
    }

    @Test
    @DisplayName("changing a key back and forth fires two events (each change is real)")
    void backAndForthFiresTwoEvents() {
        AtomicReference<Object> ref = new AtomicReference<>(false);
        registry.register("flag", binding("flag", boolean.class, ref));

        registry.onSourceChange("src", Map.of("flag", "true"));
        registry.onSourceChange("src", Map.of("flag", "false"));

        verify(publisher, times(2)).publishEvent(any(MarzEvent.class));
    }

    @Test
    @DisplayName("HashMap allowing nulls passes through unchanged keys without firing events")
    void hashMapBypassDedup() {
        AtomicReference<Object> ref = new AtomicReference<>(false);
        registry.register("flag", binding("flag", boolean.class, ref));

        Map<String, String> changes = new HashMap<>();
        changes.put("flag", "false"); // same as initial → no event
        registry.onSourceChange("src", changes);

        verify(publisher, never()).publishEvent(any());
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private FieldBinding binding(String key, String sourceUri) {
        return new FieldBinding(
                new Object(), "TestBean", "testField", null,
                new AtomicReference<>(false), boolean.class,
                key, sourceUri, false);
    }

    private FieldBinding binding(String key, Class<?> type, AtomicReference<Object> ref) {
        return new FieldBinding(
                new Object(), "TestBean", "testField", null, ref, type,
                key, "file:///c.yml", false);
    }
}
