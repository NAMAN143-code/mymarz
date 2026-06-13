package com.mymarz.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAN-98: {@link FileConfigSource#advanceCache} advances the cache to the new state
 * but keeps failed keys pinned to their previous value (or absent) so the next diff
 * re-detects and retries them instead of losing them forever. Shared by the file and
 * HTTP sources (both diff against this cache), so this is the source-layer retry guard
 * for both paths.
 */
class FileConfigSourceAdvanceCacheTest {

    @Test
    @DisplayName("no failures: cache advances fully to the new state")
    void noFailures_advancesFully() {
        Map<String, String> prev = Map.of("a", "1", "b", "2");
        Map<String, String> next = Map.of("a", "9", "b", "2");

        Map<String, String> result = FileConfigSource.advanceCache(prev, next, Set.of());

        assertThat(result).isEqualTo(next);
    }

    @Test
    @DisplayName("failed MODIFIED key is pinned to its previous value so the change re-detects")
    void failedModified_retainsPreviousValue() {
        Map<String, String> prev = Map.of("good", "1", "bad", "1");
        Map<String, String> next = Map.of("good", "2", "bad", "xyz"); // bad failed to coerce

        Map<String, String> result = FileConfigSource.advanceCache(prev, next, Set.of("bad"));

        assertThat(result).containsEntry("good", "2");   // good advanced
        assertThat(result).containsEntry("bad", "1");    // bad pinned to old value → re-diffs next cycle
    }

    @Test
    @DisplayName("failed ADDED key is dropped from the cache so the add re-detects")
    void failedAdded_keptAbsent() {
        Map<String, String> prev = Map.of("a", "1");
        Map<String, String> next = Map.of("a", "1", "added", "xyz"); // newly added, failed

        Map<String, String> result = FileConfigSource.advanceCache(prev, next, Set.of("added"));

        assertThat(result).containsEntry("a", "1");
        assertThat(result).doesNotContainKey("added"); // absent → next diff re-detects the add
    }

    @Test
    @DisplayName("failed REMOVED key is restored so the removal re-detects")
    void failedRemoved_restoresPreviousValue() {
        Map<String, String> prev = Map.of("a", "1", "gone", "old");
        Map<String, String> next = Map.of("a", "1"); // 'gone' removed from source; its revert failed

        Map<String, String> result = FileConfigSource.advanceCache(prev, next, Set.of("gone"));

        assertThat(result).containsEntry("gone", "old"); // restored → next diff re-detects the removal
    }

    @Test
    @DisplayName("mixed batch: good keys advance, failed keys retained for retry")
    void mixedBatch() {
        Map<String, String> prev = new LinkedHashMap<>();
        prev.put("keep", "k");
        prev.put("mod", "old");
        Map<String, String> next = new LinkedHashMap<>();
        next.put("keep", "k");
        next.put("mod", "bad");   // failed
        next.put("add", "good");  // applied

        Map<String, String> result = FileConfigSource.advanceCache(prev, next, Set.of("mod"));

        assertThat(result).containsEntry("keep", "k");
        assertThat(result).containsEntry("mod", "old");  // pinned for retry
        assertThat(result).containsEntry("add", "good"); // applied, advanced
    }
}
