package com.mymarz.core;

import com.mymarz.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for all {@code @Marz} field bindings.
 *
 * <p>Maintains a <strong>reverse index</strong>: config key to list of memory
 * locations ({@link FieldBinding}) bound to that key. When a source reports
 * a change, ONLY the bindings for the changed key are swapped. Everything
 * else is untouched.</p>
 *
 * <p>Thread-safety: {@link ConcurrentHashMap} for the index,
 * {@link CopyOnWriteArrayList} for binding lists (writes are rare — only
 * during Spring context startup; reads are frequent — every time a source
 * change is detected).</p>
 *
 * @since 1.0.0
 */
public class MarzRegistry {

    private static final Logger log = LoggerFactory.getLogger(MarzRegistry.class);

    // ═══════════════════════════════════════════════════════════════════
    // THE REVERSE INDEX — the core data structure of the entire system
    //
    // Key:   config key (e.g., "feature.newCheckout.enabled")
    // Value: all FieldBindings that read from this key
    //
    // When a key changes, we look up this map ONCE and get every memory
    // location that needs updating. O(1) lookup, not O(n) scan.
    // ═══════════════════════════════════════════════════════════════════
    private final Map<String, List<FieldBinding>> keyToBindings = new ConcurrentHashMap<>();

    // Forward index: source URI -> list of keys (for health checks, heartbeat)
    private final Map<String, List<String>> sourceToKeys = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher eventPublisher;
    private final TypeCoercer typeCoercer;

    public MarzRegistry(ApplicationEventPublisher eventPublisher, TypeCoercer typeCoercer) {
        this.eventPublisher = eventPublisher;
        this.typeCoercer = typeCoercer;
    }

    // ═══════════════════════════════════════════════════════════════════
    // REGISTRATION — called by BeanPostProcessor during Spring startup
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Register a {@code @Marz} field binding.
     * Called once per annotated field during BeanPostProcessor scanning.
     *
     * @param key     the config key (e.g., "feature.newCheckout.enabled")
     * @param binding the field binding (bean + AtomicReference + metadata)
     */
    public void register(String key, FieldBinding binding) {
        keyToBindings
                .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                .add(binding);

        sourceToKeys
                .computeIfAbsent(binding.sourceUri(), s -> new CopyOnWriteArrayList<>())
                .add(key);

        if (binding.sensitive()) {
            log.info("Registered @Marz field: {}.{} key='{}' [SENSITIVE]",
                    binding.beanClassName(), binding.fieldName(), key);
        } else {
            log.info("Registered @Marz field: {}.{} key='{}' value={}",
                    binding.beanClassName(), binding.fieldName(), key, binding.ref().get());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TARGETED SWAP — called when a source detects a change
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Process a set of changed keys from a config source.
     *
     * <p>CRITICAL: This method receives ONLY the keys that changed, not the
     * full state. It touches ONLY the AtomicReferences bound to those specific
     * keys. All other bindings are completely untouched — not read, not checked,
     * not iterated.</p>
     *
     * @param sourceId    identifier for the source that detected the change (for logging/audit)
     * @param changedKeys map of key to new raw string value (only the keys that changed)
     */
    public void onSourceChange(String sourceId, Map<String, String> changedKeys) {
        for (var entry : changedKeys.entrySet()) {
            String key = entry.getKey();
            String newRawValue = entry.getValue();

            // Reverse index lookup — O(1)
            List<FieldBinding> bindings = keyToBindings.get(key);
            if (bindings == null) {
                // Key exists in config but no @Marz field uses it. Normal. Skip.
                continue;
            }

            // Swap ONLY the bindings for THIS key
            for (FieldBinding binding : bindings) {
                Object coerced = typeCoercer.coerce(newRawValue, binding.targetType());
                Object oldValue = binding.ref().getAndSet(coerced);

                if (!Objects.equals(oldValue, coerced)) {
                    // ── VOLATILE FIELD WRITEBACK ──────────────────────────────
                    // The field is declared volatile by contract (enforced at
                    // startup by BeanPostProcessor). field.set() performs a
                    // volatile write, so all threads see the new value on their
                    // next read — ~5ns cost, zero IO on the read path.
                    //
                    // The AtomicReference above is for CAS-based dedup (this
                    // Objects.equals check) and health snapshots. The volatile
                    // field is the actual read path for application code.
                    if (binding.field() != null) {
                        try {
                            binding.field().set(binding.bean(), coerced);
                        } catch (IllegalAccessException e) {
                            log.error("Failed to write swapped value to {}.{}: {}",
                                    binding.beanClassName(), binding.fieldName(), e.getMessage());
                        }
                    }

                    // Fire event ONLY if the value actually changed
                    // (protects against duplicate WatchService events)
                    if (binding.sensitive()) {
                        log.info("MARZ: {}.{} changed [{}] *** -> ***",
                                binding.beanClassName(), binding.fieldName(), key);
                    } else {
                        log.info("MARZ: {}.{} changed [{}] {} -> {}",
                                binding.beanClassName(), binding.fieldName(), key, oldValue, coerced);
                    }

                    MarzEvent event = new MarzEvent(
                            binding.bean(), key, oldValue, coerced, sourceId, binding.sensitive());
                    eventPublisher.publishEvent(event);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY — for health checks, metrics, and BeanPostProcessor
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get all bindings for a given config key.
     *
     * @param key the config key
     * @return list of bindings (never null, may be empty)
     */
    public List<FieldBinding> getBindings(String key) {
        return keyToBindings.getOrDefault(key, Collections.emptyList());
    }

    /**
     * @return all unique source URIs that have registered fields
     */
    public List<String> getKeysForSource(String sourceUri) {
        return sourceToKeys.getOrDefault(sourceUri, Collections.emptyList());
    }

    /**
     * Returns the number of registered keys (for metrics).
     */
    public int getRegisteredKeyCount() {
        return keyToBindings.size();
    }

    /**
     * Returns the total number of field bindings across all keys (for metrics).
     */
    public int getTotalBindingCount() {
        return keyToBindings.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Returns a snapshot of all registered keys and their current values.
     * Used by the agent heartbeat to report state to the platform.
     */
    public Map<String, FieldStateSnapshot> getStateSnapshot() {
        Map<String, FieldStateSnapshot> snapshot = new ConcurrentHashMap<>();
        keyToBindings.forEach((key, bindings) -> {
            if (!bindings.isEmpty()) {
                FieldBinding first = bindings.get(0);
                Object currentValue = first.ref().get();
                snapshot.put(key, new FieldStateSnapshot(
                        currentValue,
                        first.sensitive(),
                        bindings.size(),
                        first.targetType().getSimpleName()
                ));
            }
        });
        return snapshot;
    }

    /**
     * Clear all registrations (used during shutdown).
     */
    public void clear() {
        keyToBindings.clear();
        sourceToKeys.clear();
        log.info("MarzRegistry cleared");
    }

    /**
     * Snapshot of a field's current state for reporting.
     */
    public record FieldStateSnapshot(
            Object value,
            boolean sensitive,
            int bindingCount,
            String type
    ) {}
}
