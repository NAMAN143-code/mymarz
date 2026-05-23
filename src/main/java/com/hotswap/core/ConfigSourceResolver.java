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
 * Resolves source URI strings into {@link ConfigSource} instances.
 * Manages lifecycle (start/stop) for all created sources.
 *
 * @since 1.0.0
 */
public class ConfigSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourceResolver.class);

    private final ConfigFormatParser parser;
    private final HotSwapRegistry registry;
    private final SourceStrategyResolver strategyResolver;

    private final Map<String, ConfigSource> sources = new ConcurrentHashMap<>();

    public ConfigSourceResolver(ConfigFormatParser parser, HotSwapRegistry registry,
                                 SourceStrategyResolver strategyResolver) {
        this.parser = parser;
        this.registry = registry;
        this.strategyResolver = strategyResolver;
    }

    public ConfigSource resolve(String sourceUri) {
        if (sourceUri == null || sourceUri.isEmpty()) return null;
        ConfigSource cached = sources.get(sourceUri);
        if (cached != null) return cached;
        ConfigSource created = createSource(sourceUri);
        if (created != null) sources.put(sourceUri, created);
        return created;
    }

    /** Start all created sources. Called by SmartLifecycle after BPP completes. */
    public void startAll() {
        int started = 0;
        for (ConfigSource source : sources.values()) {
            try { source.start(); started++; }
            catch (Exception e) { log.error("Failed to start source '{}': {}", source.sourceId(), e.getMessage()); }
        }
        log.info("Started {} config source(s)", started);
    }

    /** Stop all created sources. Called on context shutdown. */
    public void stopAll() {
        for (ConfigSource source : sources.values()) {
            try { source.stop(); }
            catch (Exception e) { log.error("Failed to stop source '{}': {}", source.sourceId(), e.getMessage()); }
        }
    }

    public Collection<ConfigSource> getAllSources() {
        return Collections.unmodifiableCollection(sources.values());
    }

    private ConfigSource createSource(String uri) {
        String scheme = extractScheme(uri);
        return switch (scheme) {
            case "file", "classpath" -> createFileSource(uri);
            case "http", "https" -> createHttpSource(uri);
            case "platform" -> {
                log.warn("Platform source '{}' not available — set an explicit source or connect to HotSwap Platform.", uri);
                yield null;
            }
            default -> { log.warn("Unsupported source scheme '{}': {}", scheme, uri); yield null; }
        };
    }

    private ConfigSource createFileSource(String uri) {
        Path filePath = FileConfigSource.resolveFilePath(uri);
        SourceStrategyResolver.Strategy strategy = strategyResolver.resolveFileStrategy(filePath, false);
        return new FileConfigSource(uri, parser, registry, strategy);
    }

    private ConfigSource createHttpSource(String uri) {
        return new HttpConfigSource(uri, parser, registry, HttpConfigSource.DEFAULT_POLL_INTERVAL_SECONDS);
    }

    static String extractScheme(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        int colonIndex = uri.indexOf(':');
        return colonIndex <= 0 ? uri.toLowerCase() : uri.substring(0, colonIndex).toLowerCase();
    }
}
