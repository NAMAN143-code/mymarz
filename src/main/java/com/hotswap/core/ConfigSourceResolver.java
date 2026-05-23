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
 * @since 1.0.0
 */
public class ConfigSourceResolver {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourceResolver.class);

    private final ConfigFormatParser parser;
    private final HotSwapRegistry registry;
    private final SourceStrategyResolver strategyResolver;

    private final Map<String, ConfigSource> sources = new ConcurrentHashMap<>();
    private final Map<String, Startable> lifecycleSources = new ConcurrentHashMap<>();

    public ConfigSourceResolver(ConfigFormatParser parser, HotSwapRegistry registry,
                                 SourceStrategyResolver strategyResolver) {
        this.parser = parser;
        this.registry = registry;
        this.strategyResolver = strategyResolver;
    }

    public ConfigSource resolve(String sourceUri) {
        if (sourceUri == null || sourceUri.isEmpty()) {
            return null;
        }
        // Don't cache null results (platform, unsupported schemes)
        ConfigSource cached = sources.get(sourceUri);
        if (cached != null) {
            return cached;
        }
        ConfigSource created = createSource(sourceUri);
        if (created != null) {
            sources.put(sourceUri, created);
        }
        return created;
    }

    public void startAll() {
        int started = 0;
        for (var entry : lifecycleSources.entrySet()) {
            try { entry.getValue().start(); started++; }
            catch (Exception e) { log.error("Failed to start source '{}': {}", entry.getKey(), e.getMessage()); }
        }
        log.info("Started {} config source(s)", started);
    }

    public void stopAll() {
        for (var entry : lifecycleSources.entrySet()) {
            try { entry.getValue().stop(); }
            catch (Exception e) { log.error("Failed to stop source '{}': {}", entry.getKey(), e.getMessage()); }
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
                log.warn("Platform source '{}' not available — platform agent not connected. "
                        + "Set an explicit source (e.g., source=\"file:///config.yml\") or connect to HotSwap Platform.", uri);
                yield null;
            }
            default -> { log.warn("Unsupported source scheme '{}': {}", scheme, uri); yield null; }
        };
    }

    private ConfigSource createFileSource(String uri) {
        Path filePath = FileConfigSource.resolveFilePath(uri);
        SourceStrategyResolver.Strategy strategy = strategyResolver.resolveFileStrategy(filePath, false);
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
        return colonIndex <= 0 ? uri.toLowerCase() : uri.substring(0, colonIndex).toLowerCase();
    }

    interface Startable { void start(); void stop(); }
}
