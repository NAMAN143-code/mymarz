package com.mymarz.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * Probes the runtime environment and selects the optimal
 * change detection strategy for each config source.
 *
 * <p>Per ADR-001 Addendum: the probe creates a temp file in the same
 * directory as the config file, registers a WatchService, writes to the
 * temp file, and waits up to 3 seconds for an event. If the event fires,
 * WatchService works on this filesystem.</p>
 *
 * @since 1.0.0
 */
public class SourceStrategyResolver {

    private static final Logger log = LoggerFactory.getLogger(SourceStrategyResolver.class);

    private static final long PROBE_TIMEOUT_SECONDS = 3;

    /**
     * Operating modes for file-based config sources.
     */
    public enum Strategy {
        /** Mode A: OS-level events (inotify/kqueue), zero-poll when idle. */
        WATCHSERVICE,
        /** Mode B: WebSocket push from MARZ Platform, file is bootstrap-only. */
        PLATFORM_PRIMARY,
        /** Mode C: HTTP conditional GET with ETag. */
        HTTP_POLL,
        /** Mode D: CRC32 checksum comparison every 30s (last resort). */
        DEGRADED_POLL
    }

    /**
     * Resolve the optimal strategy for a file source.
     *
     * @param filePath          the config file path
     * @param platformAvailable whether the MARZ Platform agent is connected
     * @return the strategy to use
     */
    public Strategy resolveFileStrategy(Path filePath, boolean platformAvailable) {
        // Step 1: Probe WatchService
        if (probeWatchService(filePath)) {
            log.info("WatchService probe PASSED for {} — using event-driven mode (Mode A)", filePath);
            return Strategy.WATCHSERVICE;
        }

        // Step 2: WatchService failed — is Platform available?
        if (platformAvailable) {
            log.info("WatchService probe FAILED for {}. Platform connected — promoted to push mode (Mode B)", filePath);
            return Strategy.PLATFORM_PRIMARY;
        }

        // Step 3: No WatchService, no Platform — degraded poll
        log.warn("WatchService probe FAILED for {}, no platform connection. "
                + "Falling back to 30s CRC32 polling (Mode D). "
                + "Config changes will have up to 30s propagation delay. "
                + "Connect to MARZ Platform for instant push propagation.", filePath);
        return Strategy.DEGRADED_POLL;
    }

    /**
     * Resolve strategy for an HTTP source. Always Mode C.
     *
     * @return HTTP_POLL
     */
    public Strategy resolveHttpStrategy() {
        return Strategy.HTTP_POLL;
    }

    /**
     * Probe whether WatchService works on the filesystem containing {@code filePath}.
     *
     * <p>Creates a temp file in the same directory, registers a WatchService,
     * writes a byte to the temp file, and waits up to 3 seconds for an
     * ENTRY_MODIFY event. Cleans up the temp file regardless of outcome.</p>
     *
     * @param filePath the config file whose directory to probe
     * @return true if WatchService fires events for this filesystem
     */
    boolean probeWatchService(Path filePath) {
        Path dir = filePath.getParent();
        if (dir == null) {
            log.debug("Cannot probe WatchService: filePath has no parent directory: {}", filePath);
            return false;
        }

        Path probeFile = null;
        try {
            // Create temp probe file in the same directory as the config file
            probeFile = Files.createTempFile(dir, ".marz-probe-", ".tmp");

            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);

                // Write to the probe file to trigger an event
                Files.writeString(probeFile, "probe-" + System.nanoTime());

                // Wait for the event
                WatchKey key = watcher.poll(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (key != null) {
                    // Drain events to prevent overflow
                    key.pollEvents();
                    key.reset();
                    log.debug("WatchService probe: event received for {}", dir);
                    return true;
                } else {
                    log.debug("WatchService probe: no event within {}s for {}", PROBE_TIMEOUT_SECONDS, dir);
                    return false;
                }
            }
        } catch (IOException e) {
            log.debug("WatchService probe failed with IOException for {}: {}", dir, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("WatchService probe interrupted for {}", dir);
            return false;
        } catch (Exception e) {
            log.debug("WatchService probe failed for {}: {}", dir, e.getMessage());
            return false;
        } finally {
            // Clean up probe file
            if (probeFile != null) {
                try {
                    Files.deleteIfExists(probeFile);
                } catch (IOException e) {
                    log.trace("Could not delete probe file: {}", probeFile);
                }
            }
        }
    }
}
