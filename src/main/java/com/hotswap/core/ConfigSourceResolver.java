package com.hotswap.core;

import com.hotswap.source.ConfigFormatParser;
import com.hotswap.source.FileConfigSource;
import com.hotswap.source.HttpConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves source URI strings into {@link ConfigSource} instances,
 * selecting the appropriate implementation and strategy.
 *
 * <p>Uses {@link SourceStrategyResolver} to probe the filesystem and
 * determine the optimal change detection mode for file sources.</p>
 *
 * @since 1.0.0
 */
public class ConfigSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourceResolver.class);

    private final ConfigFormatParser parser;
    private final HotSwapRegistry registry;
    private final SourceStrategyResolver strategyResolver;

    /** Cache of created sources — one per unique source URI */
    private final Map<String, ConfigSource> sources = new ConcurrentHashMap<>();

    /** Sources that have a start/stop lifecycle (file watchers, HTTP pollers) */
    private final Map<String, Startable> lifecycleSources = new ConcurrentHashMap<>();

    public ConfigSourceResolver(ConfigFormatParser parser,
                                 HotSwapRegistry registry,
                                 SourceStrategyResolver strategyResolver) {
        this.parser = parser;
        this.registry = registry;
        this.strategyResolver = strategyResolver;
    }

    /**
     * Resolve or create a ConfigSource for the given URI.
     * Cached — calling twice with the same URI returns the same instance.
     *
     * @param sourceUri the source URI (e.g., "file:///config.yml", "http://host/config")
     * @return the ConfigSource, or null if the scheme is unsupported
     */
    public ConfigSource resolve(String sourceUri) {
        return sources.computeIfAbsent(sourceUri, this::createSource);
    }

    /**
     * Start all lifecycle-aware sources (file watchers, HTTP pollers).
     * Called by SmartLifecycle after all beans are post-processed.
     */
    public void startAll() {
        int started = 0;
        for (var entry : lifecycleSources.entrySet()) {
            try {
                entry.getValue().start();
                started++;
            } catch (Exception e) {
                log.error("Failed to start source '{}': {}", entry.getKey(), e.getMessage(), e);
            }
        }
        log.info("Started {} config source(s)", started);
    }

    /**
     * Stop all lifecycle-aware sources.
     */
    public void stopAll() {
        for (var entry : lifecycleSources.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (Exception e) {
                log.error("Failed to stop source '{}': {}", entry.getKey(), e.getMessage(), e);
            }
        }
        log.info("Stopped all config sources");
    }

    /**
     * @return all created sources (unmodifiable)
     */
    public Collection<ConfigSource> getAllSources() {
        return Collections.unmodifiableCollection(sources.values());
    }

    // ═══════════════════════════════════════════════════════════════════

    private ConfigSource createSource(String uri) {
        String scheme = extractScheme(uri);

        return switch (scheme) {
            case "file" -> createFileSource(uri);
            case "http", "https" -> createHttpSource(uri);
            case "classpath" -> createFileSource(uri); // Classpath handled as file
            case "platform" -> {
                log.debug("Platform source '{}' — will be activated when agent connects", uri);
                yield null; // Platform sources are handled by the agent, not locally
            }
            default -> {
                log.warn("Unsupported source scheme '{}' in URI: {}", scheme, uri);
                yield null;
            }
        };
    }

    private ConfigSource createFileSource(String uri) {
        Path filePath = FileConfigSource.resolveFilePath(uri);
        boolean platformAvailable = false; // TODO: check platform agent status
        SourceStrategyResolver.Strategy strategy = strategyResolver.resolveFileStrategy(filePath, platformAvailable);

        FileConfigSource source = new FileConfigSource(uri, parser, registry, strategy);
        lifecycleSources.put(uri, new Startable() {
            @Override public void start() { source.start(); }
            @Override public void stop() { source.stop(); }
        });

        return source;
    }

    private ConfigSource createHttpSource(String uri) {
        HttpConfigSource source = new HttpConfigSource(uri, parser, registry,
                HttpConfigSource.DEFAULT_POLL_INTERVAL_SECONDS);
        lifecycleSources.put(uri, new Startable() {
            @Override public void start() { source.start(); }
            @Override public void stop() { source.stop(); }
        });

        return source;
    }

    static String extractScheme(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        int colonIndex = uri.indexOf(':');
        if (colonIndex <= 0) return uri.toLowerCase();
        return uri.substring(0, colonIndex).toLowerCase();
    }

    /**
     * Internal lifecycle interface for sources that need start/stop.
     */
    interface Startable {
        void start();
        void stop();
    }
}
