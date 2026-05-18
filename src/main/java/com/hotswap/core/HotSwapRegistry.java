package com.hotswap.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for all {@code @HotSwap}-annotated fields.
 *
 * <p>Fields are grouped by their source URI. The polling engine queries
 * this registry to determine which sources to poll and which fields
 * to update when a source changes.</p>
 *
 * <p>Key design choice from ADR-001: poll per-source, not per-field.
 * If 50 fields point to the same config file, only one poll runs.</p>
 *
 * @since 1.0.0
 */
public class HotSwapRegistry {

    private static final Logger log = LoggerFactory.getLogger(HotSwapRegistry.class);

    /** source URI → list of field holders for that source */
    private final Map<String, List<HotSwapFieldHolder>> fieldsBySource = new ConcurrentHashMap<>();

    /** key → field holder for fast key-based lookup */
    private final Map<String, HotSwapFieldHolder> fieldsByKey = new ConcurrentHashMap<>();

    /** source URI → resolved ConfigSource instance */
    private final Map<String, ConfigSource> configSources = new ConcurrentHashMap<>();

    /**
     * Register a field holder.
     *
     * @param holder the field holder to register
     */
    public void register(HotSwapFieldHolder holder) {
        String source = holder.getSource();
        String key = holder.getKey();

        fieldsBySource.computeIfAbsent(source, k -> new CopyOnWriteArrayList<>()).add(holder);
        fieldsByKey.put(key, holder);

        log.info("Registered @HotSwap field: key='{}', source='{}', bean={}.{}",
                key, source,
                holder.getBean().getClass().getSimpleName(),
                holder.getField().getName());
    }

    /**
     * Register a config source implementation for a source URI.
     *
     * @param sourceUri    the source URI
     * @param configSource the config source implementation
     */
    public void registerSource(String sourceUri, ConfigSource configSource) {
        configSources.put(sourceUri, configSource);
        log.debug("Registered ConfigSource: {} → {}", sourceUri, configSource.getClass().getSimpleName());
    }

    /**
     * Get all field holders for a given source URI.
     *
     * @param sourceUri the source URI
     * @return list of holders (never null, may be empty)
     */
    public List<HotSwapFieldHolder> getFieldsForSource(String sourceUri) {
        return fieldsBySource.getOrDefault(sourceUri, Collections.emptyList());
    }

    /**
     * Get a field holder by its config key.
     *
     * @param key the config key
     * @return the holder, or null if not registered
     */
    public HotSwapFieldHolder getFieldByKey(String key) {
        return fieldsByKey.get(key);
    }

    /**
     * Get the ConfigSource for a given source URI.
     *
     * @param sourceUri the source URI
     * @return the config source, or null if not registered
     */
    public ConfigSource getConfigSource(String sourceUri) {
        return configSources.get(sourceUri);
    }

    /**
     * @return all unique source URIs that have registered fields
     */
    public Collection<String> getAllSourceUris() {
        return Collections.unmodifiableSet(fieldsBySource.keySet());
    }

    /**
     * Get the minimum poll interval across all fields for a source.
     * Per ADR-001: shortest interval wins for shared sources.
     *
     * @param sourceUri the source URI
     * @return minimum poll interval in ms, or 5000 if no fields
     */
    public long getMinPollInterval(String sourceUri) {
        List<HotSwapFieldHolder> holders = fieldsBySource.get(sourceUri);
        if (holders == null || holders.isEmpty()) {
            return 5000L;
        }
        return holders.stream()
                .mapToLong(HotSwapFieldHolder::getPollInterval)
                .filter(interval -> interval > 0) // Exclude push-only (-1)
                .min()
                .orElse(5000L);
    }

    /**
     * @return total count of registered fields
     */
    public int getFieldCount() {
        return fieldsByKey.size();
    }

    /**
     * @return total count of unique sources
     */
    public int getSourceCount() {
        return fieldsBySource.size();
    }

    /**
     * Clear all registrations (used during shutdown).
     */
    public void clear() {
        fieldsBySource.clear();
        fieldsByKey.clear();
        configSources.clear();
        log.info("HotSwapRegistry cleared");
    }
}
