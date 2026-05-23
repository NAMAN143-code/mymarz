package com.hotswap.source;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive tests for {@link FileConfigSource#diff(Map, Map)}.
 * This is the core change detection logic — must handle every edge case.
 */
class FileConfigSourceDiffTest {

    @Test
    void noChanges_returnsEmptyMap() {
        Map<String, String> state = Map.of("a", "1", "b", "2");
        assertThat(FileConfigSource.diff(state, state)).isEmpty();
    }

    @Test
    void sameContent_returnsEmptyMap() {
        Map<String, String> old = Map.of("a", "1", "b", "2");
        Map<String, String> now = Map.of("a", "1", "b", "2");
        assertThat(FileConfigSource.diff(old, now)).isEmpty();
    }

    @Test
    void oneKeyModified() {
        Map<String, String> old = Map.of("a", "1", "b", "2", "c", "3");
        Map<String, String> now = Map.of("a", "1", "b", "CHANGED", "c", "3");

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(1);
        assertThat(diff).containsEntry("b", "CHANGED");
    }

    @Test
    void multipleKeysModified() {
        Map<String, String> old = Map.of("a", "1", "b", "2", "c", "3");
        Map<String, String> now = Map.of("a", "X", "b", "2", "c", "Z");

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(2);
        assertThat(diff).containsEntry("a", "X");
        assertThat(diff).containsEntry("c", "Z");
    }

    @Test
    void keyAdded() {
        Map<String, String> old = Map.of("a", "1");
        Map<String, String> now = Map.of("a", "1", "b", "NEW");

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(1);
        assertThat(diff).containsEntry("b", "NEW");
    }

    @Test
    void keyRemoved() {
        Map<String, String> old = Map.of("a", "1", "b", "2");
        Map<String, String> now = Map.of("a", "1");

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(1);
        assertThat(diff).containsKey("b");
        assertThat(diff.get("b")).isNull();
    }

    @Test
    void addedAndRemovedAndModified() {
        Map<String, String> old = Map.of("keep", "same", "modify", "old", "remove", "gone");
        Map<String, String> now = Map.of("keep", "same", "modify", "new", "added", "fresh");

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(3);
        assertThat(diff).containsEntry("modify", "new");
        assertThat(diff).containsEntry("added", "fresh");
        assertThat(diff).containsKey("remove");
        assertThat(diff.get("remove")).isNull();
    }

    @Test
    void emptyOldState_allKeysAreNew() {
        Map<String, String> old = Map.of();
        Map<String, String> now = Map.of("a", "1", "b", "2");

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(2);
        assertThat(diff).containsEntry("a", "1");
        assertThat(diff).containsEntry("b", "2");
    }

    @Test
    void emptyNewState_allKeysRemoved() {
        Map<String, String> old = Map.of("a", "1", "b", "2");
        Map<String, String> now = Map.of();

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(2);
        assertThat(diff.get("a")).isNull();
        assertThat(diff.get("b")).isNull();
    }

    @Test
    void bothEmpty_returnsEmptyMap() {
        assertThat(FileConfigSource.diff(Map.of(), Map.of())).isEmpty();
    }

    @Test
    void valueChangedToEmptyString() {
        Map<String, String> old = Map.of("key", "value");
        Map<String, String> now = Map.of("key", "");

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(1);
        assertThat(diff).containsEntry("key", "");
    }

    @Test
    void valueChangedFromEmptyString() {
        Map<String, String> old = Map.of("key", "");
        Map<String, String> now = Map.of("key", "value");

        Map<String, String> diff = FileConfigSource.diff(old, now);

        assertThat(diff).hasSize(1);
        assertThat(diff).containsEntry("key", "value");
    }
}
