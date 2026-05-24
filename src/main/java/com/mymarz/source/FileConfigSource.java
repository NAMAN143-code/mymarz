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

    private final Path filePath;
    private final String uri;
    private final ConfigFormatParser parser;
    private final MarzRegistry registry;
    private final SourceStrategyResolver.Strategy strategy;

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

        // Initial load
        this.cachedState = loadAndParse();
        this.lastKnownChecksum = computeChecksum();

        log.info("FileConfigSource created: {} ({} keys, strategy={})",
                filePath, cachedState.size(), strategy);
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
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            Path dir = filePath.getParent();
            dir.register(watcher,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);

            while (running) {
                WatchKey key = watcher.take(); // BLOCKS — zero CPU when idle

                // Debounce: wait 50ms for editors that do multi-step writes
                Thread.sleep(DEBOUNCE_MS);

                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (changed != null && filePath.getFileName().equals(changed)) {
                        relevant = true;
                    }
                }

                if (relevant) {
                    detectAndPushChanges();
                }

                if (!key.reset()) {
                    log.warn("WatchKey invalidated for {}. File may have been deleted.", filePath);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("WatchService thread interrupted for {}", filePath);
        } catch (IOException e) {
            log.error("WatchService failed for {}: {}. Falling back to safety-net poll.", filePath, e.getMessage());
        }
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

            Map<String, String> newState = loadAndParse();
            Map<String, String> changedKeys = diff(cachedState, newState);

            if (!changedKeys.isEmpty()) {
                // Update cached state BEFORE pushing to registry
                cachedState = newState;
                lastKnownChecksum = computeChecksum();

                log.debug("Detected {} changed key(s) in {}", changedKeys.size(), filePath);
                registry.onSourceChange(sourceId(), changedKeys);
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

    private ScheduledExecutorService createScheduledExecutor(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
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
