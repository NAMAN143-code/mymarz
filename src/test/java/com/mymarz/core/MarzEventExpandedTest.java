package com.mymarz.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Expanded {@link MarzEvent} coverage:
 * <ul>
 *     <li>Behaviour inherited from {@link ApplicationEvent} (timestamp, source)</li>
 *     <li>toString format and key presence under both sensitive flags</li>
 *     <li>Null oldValue / newValue (a real possibility when keys are removed)</li>
 *     <li>Complex value types (Map, List, custom objects)</li>
 *     <li>Constructor null bean fails per ApplicationEvent contract</li>
 * </ul>
 */
class MarzEventExpandedTest {

    // ═══════════════════════════════════════════════════════════════════
    // INHERITANCE FROM ApplicationEvent
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("is a Spring ApplicationEvent")
    void isApplicationEvent() {
        MarzEvent e = new MarzEvent(this, "k", "old", "new", "src", false);
        assertThat(e).isInstanceOf(ApplicationEvent.class);
    }

    @Test
    @DisplayName("timestamp is set at construction time")
    void timestampSetAtConstruction() {
        long before = System.currentTimeMillis();
        MarzEvent e = new MarzEvent(this, "k", null, null, "src", false);
        long after = System.currentTimeMillis();

        assertThat(e.getTimestamp()).isBetween(before, after);
    }

    @Test
    @DisplayName("source from ApplicationEvent matches the bean argument")
    void sourceMatchesBean() {
        Object bean = new Object();
        MarzEvent e = new MarzEvent(bean, "k", null, null, "src", false);

        assertThat(e.getSource()).isSameAs(bean);
    }

    @Test
    @DisplayName("ApplicationEvent rejects null source (NPE from super constructor)")
    void nullBeanRejectedBySuper() {
        // Spring's ApplicationEvent throws IllegalArgumentException on null source.
        assertThatThrownBy(() ->
                new MarzEvent(null, "k", "old", "new", "src", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ═══════════════════════════════════════════════════════════════════
    // NULL VALUES
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("null oldValue is allowed (key was previously unset / added)")
    void nullOldValue() {
        MarzEvent e = new MarzEvent(this, "new.key", null, "value", "src", false);

        assertThat(e.getOldValue()).isNull();
        assertThat(e.getNewValue()).isEqualTo("value");
    }

    @Test
    @DisplayName("null newValue is allowed (key was removed)")
    void nullNewValue() {
        MarzEvent e = new MarzEvent(this, "removed.key", "value", null, "src", false);

        assertThat(e.getOldValue()).isEqualTo("value");
        assertThat(e.getNewValue()).isNull();
    }

    @Test
    @DisplayName("both old and new can be null (degenerate but allowed)")
    void bothNull() {
        MarzEvent e = new MarzEvent(this, "k", null, null, "src", false);

        assertThat(e.getOldValue()).isNull();
        assertThat(e.getNewValue()).isNull();
        // toString must not NPE
        assertThat(e.toString()).contains("k");
    }

    @Test
    @DisplayName("sensitive event with null values still masks in toString")
    void sensitiveNullValuesMasked() {
        MarzEvent e = new MarzEvent(this, "secret.k", null, null, "src", true);

        String s = e.toString();
        assertThat(s).contains("***");
        assertThat(s).doesNotContain("null");
    }

    // ═══════════════════════════════════════════════════════════════════
    // COMPLEX VALUE TYPES
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Map values are exposed and rendered via toString")
    void mapValues() {
        Map<String, Integer> oldMap = Map.of("a", 1);
        Map<String, Integer> newMap = Map.of("a", 2);

        MarzEvent e = new MarzEvent(this, "config.map", oldMap, newMap, "src", false);

        assertThat(e.getOldValue()).isEqualTo(oldMap);
        assertThat(e.getNewValue()).isEqualTo(newMap);
        assertThat(e.toString()).contains("config.map");
    }

    @Test
    @DisplayName("List values are exposed via accessors")
    void listValues() {
        List<String> oldList = List.of("a");
        List<String> newList = List.of("a", "b");

        MarzEvent e = new MarzEvent(this, "items", oldList, newList, "src", false);

        assertThat(e.getOldValue()).isEqualTo(oldList);
        assertThat(e.getNewValue()).isEqualTo(newList);
    }

    @Test
    @DisplayName("custom object toString participates in event toString")
    void customObjectToString() {
        Object oldVal = new Object() {
            @Override public String toString() { return "OLD-CUSTOM"; }
        };
        Object newVal = new Object() {
            @Override public String toString() { return "NEW-CUSTOM"; }
        };

        MarzEvent e = new MarzEvent(this, "k", oldVal, newVal, "src", false);

        assertThat(e.toString()).contains("OLD-CUSTOM").contains("NEW-CUSTOM");
    }

    // ═══════════════════════════════════════════════════════════════════
    // toString — STRUCTURE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("toString is consistent across calls (deterministic)")
    void toStringDeterministic() {
        MarzEvent e = new MarzEvent(this, "k", "old", "new", "src", false);
        assertThat(e.toString()).isEqualTo(e.toString());
    }

    @Test
    @DisplayName("toString includes the source identifier")
    void toStringIncludesSource() {
        MarzEvent e = new MarzEvent(this, "k", 1, 2, "platform://marz", false);
        assertThat(e.toString()).contains("platform://marz");
    }

    @Test
    @DisplayName("sensitive toString does not include any user-supplied source-uri masking")
    void sensitiveToStringStillShowsSource() {
        // Source URI is metadata, not a secret — never masked.
        MarzEvent e = new MarzEvent(this, "k", "secret-old", "secret-new",
                "file:///etc/secrets.yml", true);
        assertThat(e.toString()).contains("file:///etc/secrets.yml");
    }

    // ═══════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("all five getters return constructor inputs")
    void allGettersRoundTrip() {
        MarzEvent e = new MarzEvent(this, "K", "O", "N", "S", true);

        assertThat(e.getKey()).isEqualTo("K");
        assertThat(e.getOldValue()).isEqualTo("O");
        assertThat(e.getNewValue()).isEqualTo("N");
        assertThat(e.getConfigSource()).isEqualTo("S");
        assertThat(e.isSensitive()).isTrue();
    }
}
