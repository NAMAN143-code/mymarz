package com.mymarz.source;

import com.mymarz.core.ConfigSource;
import com.mymarz.core.MarzRegistry;
import com.mymarz.core.SourceStrategyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/**
 * Event-driven config source backed by a local file.
 *
 * <p>Key design decisions from ADR-001 Amendment:</p>
 * <ul>
 *   <li>Mode A: WatchService (OS-level event), zero CPU/IO when idle</li>
 *   <li>Mode D: Degraded CRC32 poll every 30s (NFS, Docker macOS fallback)</li>
 *   <li>50ms debounce window for editor double-writes (vim, IntelliJ)</li>
 *   <li>Safety-net CRC32 poll every 60s in Mode A (insurance)</li>
 *   <li>On change: read file ONCE, diff against cached state, push ONLY
 *       changed keys to {@link MarzRegistry#onSourceChange}</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class FileConfigSource implements ConfigSource {

    private static final Logger log = LoggerFactory.getLogger(FileConfigSource.class);

    static final long DEBOUNCE_MS = 50;
    static final long SAFETY_NET_INTERVAL_SECONDS = 60;
    static final long DEGRADED_POLL_INTERVAL_SECONDS = 30;
    /** Backoff before re-arming the watch after a transient IO error (KAN-100). */
    static final long WATCH_REARM_BACKOFF_MS = 1000;

    /** Marker file Kubernetes places in a projected ConfigMap/Secret volume directory. */
    static final String CONFIGMAP_DATA_LINK = "..data";

    private final Path filePath;
    private final String uri;
    private final ConfigFormatParser parser;
    private final MarzRegistry registry;
    private final SourceStrategyResolver.Strategy strategy;
    private final boolean configMapMount;

    private volatile Map<String, String> cachedState = Map.of();
    private volatile long lastKnownChecksum;
    private volatile boolean running = false;

    private Thread watchThread;
    private ScheduledExecutorService safetyNetExecutor;

    public FileConfigSource(String uri,
                            ConfigFormatParser parser,
                            MarzRegistry registry,
                            SourceStrategyResolver.Strategy strategy) {
        this.uri = uri;
        this.parser = parser;
        this.registry = registry;
        this.strategy = strategy;
        this.filePath = resolveFilePath(uri);
        this.configMapMount = isConfigMapMount(filePath);

        // Initial load
        this.cachedState = loadAndParse();
        this.lastKnownChecksum = computeChecksum();

        // KAN-100: surface the resolved detection mode at startup for supportability.
        if (configMapMount) {
            log.info("FileConfigSource created: {} ({} keys, strategy={}, mount=KUBERNETES_CONFIGMAP "
                    + "— watching '..data' symlink swaps)", filePath, cachedState.size(), strategy);
        } else {
            log.info("FileConfigSource created: {} ({} keys, strategy={}, mount=PLAIN_FILE)",
                    filePath, cachedState.size(), strategy);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ConfigSource contract
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public String resolve(String key) {
        return cachedState.get(key);
    }

    @Override
    public boolean isAvailable() {
        return Files.exists(filePath) && Files.isReadable(filePath);
    }

    @Override
    public String sourceId() {
        return "file:" + filePath.toAbsolutePath();
    }

    @Override
    public String scheme() {
        return "file";
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Start the change detection mechanism based on the resolved strategy.
     */
    @Override
    public void start() {
        if (running) return;
        running = true;

        switch (strategy) {
            case WATCHSERVICE -> startWatchServiceMode();
            case DEGRADED_POLL -> startDegradedPollMode();
            default -> log.info("FileConfigSource {}: no active watch (strategy={})", filePath, strategy);
        }
    }

    /**
     * Stop all background threads.
     */
    @Override
    public void stop() {
        running = false;

        if (watchThread != null) {
            watchThread.interrupt();
        }

        if (safetyNetExecutor != null && !safetyNetExecutor.isShutdown()) {
            safetyNetExecutor.shutdown();
            try {
                if (!safetyNetExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    safetyNetExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                safetyNetExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("FileConfigSource stopped: {}", filePath);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public SourceStrategyResolver.Strategy getStrategy() {
        return strategy;
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODE A: WatchService (event-driven, zero-poll)
    // ═══════════════════════════════════════════════════════════════════

    private void startWatchServiceMode() {
        // Thread 1: WatchService listener
        watchThread = new Thread(this::watchLoop, "marz-watcher-" + filePath.getFileName());
        watchThread.setDaemon(true);
        watchThread.start();

        // Thread 2: Safety-net CRC32 poll every 60s
        safetyNetExecutor = createScheduledExecutor("marz-safety-net-" + filePath.getFileName());
        safetyNetExecutor.scheduleAtFixedRate(
                this::safetyNetPoll,
                SAFETY_NET_INTERVAL_SECONDS,
                SAFETY_NET_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        log.info("FileConfigSource Mode A started: WatchService + 60s safety-net for {}", filePath);
    }

    private void watchLoop() {
        // KAN-100: an outer resilience loop re-arms the watch instead of giving up
        // event-driven detection on a transient IO error. A Kubernetes ConfigMap atomic
        // swap momentarily creates and renames away a '..data_tmp' symlink; on a polling
        // WatchService (macOS, NFS, some containers) a directory snapshot taken mid-swap
        // can throw NoSuchFileException out of register()/take(). Previously that killed
        // the watch thread for the lifetime of the process and silently degraded the
        // source to the 60s safety-net poll — defeating the whole fix. Now we back off
        // briefly, re-register, and reconcile, so the swap is applied via the watch path.
        while (running) {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                Path dir = filePath.getParent();
                // Also register ENTRY_DELETE: ConfigMap volumes update by atomically
                // swapping the '..data' directory symlink, which surfaces as CREATE/DELETE
                // on '..data' (and the timestamped data dir) — NOT as an ENTRY_MODIFY on
                // the config filename.
                dir.register(watcher,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);

                // Reconcile any change that landed before this (re-)arm — e.g. a swap that
                // completed during a re-arm backoff window. Idempotent: a no-op when the
                // diff is empty.
                detectAndPushChanges();

                while (running) {
                    WatchKey key = watcher.take(); // BLOCKS — zero CPU when idle

                    // Debounce: wait 50ms for editors that do multi-step writes
                    Thread.sleep(DEBOUNCE_MS);

                    boolean relevant = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            relevant = true; // events were dropped — re-read to be safe
                        } else if (isRelevantEvent(event)) {
                            relevant = true;
                        }
                    }

                    if (relevant) {
                        detectAndPushChanges();
                    }

                    if (!key.reset()) {
                        log.warn("WatchKey invalidated for {} — re-arming watch.", filePath);
                        break; // re-register via the outer loop
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("WatchService thread interrupted for {}", filePath);
                return; // stop() requested — exit the thread
            } catch (IOException e) {
                log.warn("WatchService error for {}: {} — re-arming watch (safety-net poll remains active).",
                        filePath, e.getMessage());
                if (!sleepBeforeReArm()) {
                    return; // interrupted while backing off
                }
            }
        }
    }

    /**
     * Decide whether a directory watch event should trigger a re-read.
     *
     * <p>Plain files: only events for the config filename matter. ConfigMap mounts
     * (KAN-100): the config filename is a stable symlink that never itself changes;
     * the update arrives as CREATE/DELETE on Kubernetes' hidden {@code ..}-prefixed
     * entries ({@code ..data}, {@code ..data_tmp}, {@code ..<timestamp>}), so any of
     * those is treated as a change trigger. The subsequent {@code detectAndPushChanges}
     * re-reads through the symlink and diffs, so a false positive is a cheap no-op.</p>
     */
    private boolean isRelevantEvent(WatchEvent<?> event) {
        Object ctx = event.context();
        if (!(ctx instanceof Path changed)) {
            return false;
        }
        String name = changed.getFileName().toString();
        if (filePath.getFileName().toString().equals(name)) {
            return true;
        }
        return configMapMount && name.startsWith("..");
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODE D: Degraded CRC32 poll (last resort)
    // ═══════════════════════════════════════════════════════════════════

    private void startDegradedPollMode() {
        safetyNetExecutor = createScheduledExecutor("marz-degraded-poll-" + filePath.getFileName());
        safetyNetExecutor.scheduleAtFixedRate(
                this::safetyNetPoll,
                DEGRADED_POLL_INTERVAL_SECONDS,
                DEGRADED_POLL_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        log.info("FileConfigSource Mode D started: CRC32 poll every {}s for {}",
                DEGRADED_POLL_INTERVAL_SECONDS, filePath);
    }

    // ═══════════════════════════════════════════════════════════════════
    // SAFETY-NET CRC32 POLL (shared by Mode A and Mode D)
    // ═══════════════════════════════════════════════════════════════════

    private void safetyNetPoll() {
        try {
            if (!Files.exists(filePath)) {
                log.trace("Safety-net poll: file does not exist: {}", filePath);
                return;
            }

            long currentChecksum = computeChecksum();
            if (currentChecksum != lastKnownChecksum) {
                log.debug("Safety-net poll detected change for {} (CRC {} -> {})",
                        filePath, lastKnownChecksum, currentChecksum);
                detectAndPushChanges();
            }
        } catch (Exception e) {
            log.error("Safety-net poll error for {}: {}", filePath, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHANGE DETECTION + TARGETED PUSH
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Read file, diff against cached state, push only changed keys to registry.
     */
    private synchronized void detectAndPushChanges() {
        try {
            if (!Files.isReadable(filePath)) {
                // Retry once after 10ms (file may be mid-write)
                Thread.sleep(10);
                if (!Files.isReadable(filePath)) {
                    log.warn("Config file temporarily unreadable: {}", filePath);
                    return;
                }
            }

            Map<String, String> previousState = cachedState;
            Map<String, String> newState = loadAndParse();
            Map<String, String> changedKeys = diff(previousState, newState);

            if (!changedKeys.isEmpty()) {
                log.debug("Detected {} changed key(s) in {}", changedKeys.size(), filePath);

                // KAN-98: push FIRST, then advance the cache only for keys that
                // actually applied. A poison value no longer drops the rest of the
                // batch (onSourceChange isolates per key) and the failed keys are
                // retained at their previous cached value so the next detection
                // cycle re-diffs and retries them instead of losing them forever.
                Set<String> failed = registry.onSourceChange(sourceId(), changedKeys);
                cachedState = advanceCache(previousState, newState, failed);

                if (failed.isEmpty()) {
                    lastKnownChecksum = computeChecksum();
                } else {
                    // Leave lastKnownChecksum stale so the 60s safety-net poll sees a
                    // mismatch and re-triggers, retrying the failed keys until they
                    // apply cleanly or the operator fixes the source value.
                    log.warn("{} key(s) failed to apply for {} and will be retried: {}",
                            failed.size(), filePath, failed);
                }
            } else {
                // File content unchanged (might have been a metadata-only change)
                lastKnownChecksum = computeChecksum();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error processing file change for {}: {}", filePath, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DIFF — static, exhaustively testable
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Compute the diff between old state and new state.
     * Returns ONLY the keys whose values changed (added, modified, or removed).
     *
     * <p>Removed keys have {@code null} as their value in the result map.</p>
     *
     * @param oldState the previously cached state
     * @param newState the newly parsed state
     * @return map of key to new value (only changed keys)
     */
    static Map<String, String> diff(Map<String, String> oldState, Map<String, String> newState) {
        Map<String, String> changes = new LinkedHashMap<>();

        // Check for added or modified keys
        for (Map.Entry<String, String> entry : newState.entrySet()) {
            String key = entry.getKey();
            String newValue = entry.getValue();
            String oldValue = oldState.get(key);

            if (!Objects.equals(oldValue, newValue)) {
                changes.put(key, newValue);
            }
        }

        // Check for removed keys
        for (String key : oldState.keySet()) {
            if (!newState.containsKey(key)) {
                changes.put(key, null);
            }
        }

        return changes;
    }

    /**
     * Advance the cached state to {@code newState}, but keep every {@code failedKey}
     * pinned to its <em>previous</em> value (or absent, if it was newly added) so the
     * next diff re-detects and retries it (KAN-98). Shared by the file and HTTP sources.
     *
     * @param previousState the state before this batch
     * @param newState      the freshly parsed source state
     * @param failedKeys    keys that failed to apply to at least one binding
     * @return the next cached state (never contains {@code null} values)
     */
    static Map<String, String> advanceCache(Map<String, String> previousState,
                                            Map<String, String> newState,
                                            Set<String> failedKeys) {
        if (failedKeys == null || failedKeys.isEmpty()) {
            return Map.copyOf(newState);
        }
        Map<String, String> next = new LinkedHashMap<>(newState);
        for (String key : failedKeys) {
            String prev = previousState.get(key);
            if (prev == null) {
                next.remove(key);      // was a newly added key — keep it absent so the add re-detects
            } else {
                next.put(key, prev);   // restore the previous value so the change re-detects
            }
        }
        return Map.copyOf(next);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private Map<String, String> loadAndParse() {
        try {
            if (!Files.exists(filePath)) {
                return Collections.emptyMap();
            }
            String content = Files.readString(filePath);
            return Map.copyOf(parser.parse(content, uri));
        } catch (IOException e) {
            log.error("Failed to load config file {}: {}", filePath, e.getMessage());
            return cachedState != null ? cachedState : Collections.emptyMap();
        }
    }

    private long computeChecksum() {
        try {
            if (!Files.exists(filePath)) return 0L;
            byte[] bytes = Files.readAllBytes(filePath);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            return crc.getValue();
        } catch (IOException e) {
            log.trace("CRC32 computation failed for {}: {}", filePath, e.getMessage());
            return lastKnownChecksum;
        }
    }

    /** Sleep the re-arm backoff after a transient watch error; false if interrupted (exit thread). */
    private boolean sleepBeforeReArm() {
        try {
            Thread.sleep(WATCH_REARM_BACKOFF_MS);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ScheduledExecutorService createScheduledExecutor(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Detect a Kubernetes-style projected volume (ConfigMap/Secret) by the presence
     * of the {@code ..data} symlink in the config file's directory (KAN-100). These
     * mounts update by atomically swapping that symlink rather than rewriting the file.
     *
     * @param filePath the resolved config file path
     * @return true if the parent directory looks like a ConfigMap/Secret mount
     */
    static boolean isConfigMapMount(Path filePath) {
        if (filePath == null) return false;
        Path parent = filePath.getParent();
        if (parent == null) return false;
        try {
            Path dataLink = parent.resolve(CONFIGMAP_DATA_LINK);
            // Symlink in projected volumes; tolerate either a symlink or a resolvable entry.
            return Files.isSymbolicLink(dataLink) || Files.exists(dataLink);
        } catch (Exception e) {
            return false;
        }
    }

    // Package-private for testing
    boolean isConfigMapMount() {
        return configMapMount;
    }

    /**
     * Converts a {@code file://} URI string to a {@link Path}.
     */
    public static Path resolveFilePath(String uri) {
        String path = uri;
        if (path.startsWith("file:///")) {
            path = path.substring(7);
        } else if (path.startsWith("file://")) {
            path = path.substring(7);
        } else if (path.startsWith("file:")) {
            path = path.substring(5);
        }
        return Paths.get(path);
    }

    // Package-private for testing
    Map<String, String> getCachedState() {
        return Collections.unmodifiableMap(cachedState);
    }
}
