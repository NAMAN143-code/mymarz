package com.mymarz.core;

import com.mymarz.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    // Keys currently in a failed state (un-coercible value). Used to dedupe
    // repeated ERROR logs: a key logs ERROR on its first failure, then DEBUG
    // while it keeps failing, and is cleared once it applies cleanly again.
    private final Set<String> failingKeys = ConcurrentHashMap.newKeySet();

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
     * <p><strong>Per-key / per-binding isolation (KAN-98):</strong> each binding is
     * applied inside its own try/catch. An un-coercible value (e.g. {@code "abc"}
     * for an {@code int} key) or a write failure affects only that key — every
     * other key and binding in the batch is still applied. A failure publishes a
     * {@linkplain MarzEvent#failure failure event} and an ERROR log (deduped), and
     * the key is returned so the calling source can keep it pending and retry it on
     * the next detection cycle instead of swallowing it permanently.</p>
     *
     * <p><strong>Removed-key semantics:</strong> a {@code null} value (key removed
     * from the source) reverts the field to its {@code @Marz(defaultValue)} when one
     * is declared; otherwise the field retains its current value. A {@code null} is
     * never written to a primitive field.</p>
     *
     * @param sourceId    identifier for the source that detected the change (for logging/audit)
     * @param changedKeys map of key to new raw string value (only the keys that changed;
     *                    a {@code null} value means the key was removed from the source)
     * @return the subset of {@code changedKeys} that failed to apply to at least one
     *         binding (empty when everything applied cleanly)
     */
    public Set<String> onSourceChange(String sourceId, Map<String, String> changedKeys) {
        Set<String> failedKeys = new HashSet<>();

        for (var entry : changedKeys.entrySet()) {
            String key = entry.getKey();
            String newRawValue = entry.getValue();

            // Reverse index lookup — O(1)
            List<FieldBinding> bindings = keyToBindings.get(key);
            if (bindings == null) {
                // Key exists in config but no @Marz field uses it. Normal. Skip.
                continue;
            }

            // Swap ONLY the bindings for THIS key — each isolated so one poison
            // value cannot drop the rest of the batch.
            boolean keyFailed = false;
            for (FieldBinding binding : bindings) {
                try {
                    applyToBinding(sourceId, key, newRawValue, binding);
                } catch (Exception ex) {
                    keyFailed = true;
                    logApplyFailure(key, binding, newRawValue, ex);
                    eventPublisher.publishEvent(MarzEvent.failure(
                            binding.bean(), key, newRawValue, sourceId, binding.sensitive(), ex));
                }
            }

            if (keyFailed) {
                failedKeys.add(key);
            } else {
                // Key applied cleanly to every binding — clear any prior failure state.
                failingKeys.remove(key);
            }
        }

        return failedKeys;
    }

    /**
     * Apply a single resolved value to one binding. Throws if coercion or the
     * volatile write fails, so the caller can isolate and report the failure.
     */
    private void applyToBinding(String sourceId, String key, String newRawValue, FieldBinding binding) {
        final Object coerced;
        if (newRawValue == null) {
            // Removed key: revert to declared default if present, else retain current value.
            String dv = binding.defaultValue();
            if (dv == null || dv.isEmpty()) {
                return; // retain last value — never write null to a (possibly primitive) field
            }
            coerced = typeCoercer.coerce(dv, binding.targetType());
        } else {
            coerced = typeCoercer.coerce(newRawValue, binding.targetType());
        }

        Object oldValue = binding.ref().getAndSet(coerced);
        if (Objects.equals(oldValue, coerced)) {
            return; // no-op — protects against duplicate WatchService events
        }

        // ── VOLATILE FIELD WRITEBACK ──────────────────────────────
        // The field is declared volatile by contract (enforced at startup by
        // BeanPostProcessor). field.set() performs a volatile write, so all
        // threads see the new value on their next read — ~5ns cost, zero IO.
        //
        // The AtomicReference above is for CAS-based dedup (this Objects.equals
        // check) and health snapshots. The volatile field is the actual read
        // path for application code.
        if (binding.field() != null) {
            try {
                binding.field().set(binding.bean(), coerced);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                // A reflective write failure is a wiring/programming error, not a bad
                // config value — retrying cannot fix it (the BeanPostProcessor always
                // makes the field accessible and correctly typed in production), so it
                // is logged and skipped rather than triggering the retry path. This
                // matches the original behavior; the KAN-98 isolate-and-retry path is
                // reserved for coercion failures (thrown above, before this point).
                log.error("MARZ: failed to write swapped value to {}.{}: {}",
                        binding.beanClassName(), binding.fieldName(), e.getMessage());
            }
        }

        if (binding.sensitive()) {
            log.info("MARZ: {}.{} changed [{}] *** -> ***",
                    binding.beanClassName(), binding.fieldName(), key);
        } else {
            log.info("MARZ: {}.{} changed [{}] {} -> {}",
                    binding.beanClassName(), binding.fieldName(), key, oldValue, coerced);
        }

        eventPublisher.publishEvent(new MarzEvent(
                binding.bean(), key, oldValue, coerced, sourceId, binding.sensitive()));
    }

    /**
     * Log an apply failure, deduping repeated failures of the same key down to
     * DEBUG so a persistently bad value cannot flood the logs every cycle.
     */
    private void logApplyFailure(String key, FieldBinding binding, String newRawValue, Exception ex) {
        String attempted = binding.sensitive() ? "***" : String.valueOf(newRawValue);
        if (failingKeys.add(key)) {
            log.error("MARZ: failed to apply key '{}' (value={}) to {}.{}: {}",
                    key, attempted, binding.beanClassName(), binding.fieldName(), ex.getMessage());
        } else {
            log.debug("MARZ: key '{}' still failing (value={}) on {}.{}: {}",
                    key, attempted, binding.beanClassName(), binding.fieldName(), ex.getMessage());
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
     * @return all registered key → bindings entries (unmodifiable)
     */
    public Map<String, List<FieldBinding>> getAllBindings() {
        return Collections.unmodifiableMap(keyToBindings);
    }

    /**
     * Remove every binding that targets the given bean instance (KAN-99).
     *
     * <p>Called by {@link MarzBeanPostProcessor} when a bean is destroyed (context
     * close, child-context teardown, scope end). Without this, {@link FieldBinding}'s
     * strong reference to the bean would pin it forever — an unbounded registry-growth
     * leak that also made the swap loop iterate dead instances.</p>
     *
     * <p>Bean identity ({@code ==}), not {@code equals}, is used so that two distinct
     * instances of the same class are treated independently.</p>
     *
     * @param bean the destroyed bean whose bindings should be dropped
     * @return the number of bindings removed
     */
    public int unregister(Object bean) {
        if (bean == null) return 0;
        int[] removed = {0};

        keyToBindings.forEach((key, bindings) -> {
            int before = bindings.size();
            bindings.removeIf(b -> b.bean() == bean);
            int dropped = before - bindings.size();
            if (dropped > 0) {
                removed[0] += dropped;
                if (bindings.isEmpty()) {
                    // Remove the key only if it is still empty (guard against a
                    // concurrent re-registration on the same key).
                    keyToBindings.remove(key, bindings);
                    sourceToKeys.values().forEach(keys -> keys.remove(key));
                }
            }
        });

        if (removed[0] > 0) {
            log.debug("Unregistered {} @Marz binding(s) for destroyed bean {}",
                    removed[0], bean.getClass().getName());
        }
        return removed[0];
    }

    /**
     * Clear all registrations (used during shutdown).
     */
    public void clear() {
        keyToBindings.clear();
        sourceToKeys.clear();
        failingKeys.clear();
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
