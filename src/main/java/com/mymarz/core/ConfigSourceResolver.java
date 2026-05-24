package com.mymarz.core;

import com.mymarz.source.ConfigFormatParser;
import com.mymarz.source.FileConfigSource;
import com.mymarz.source.HttpConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final MarzRegistry registry;
    private final SourceStrategyResolver strategyResolver;
    private final String defaultSource;
    private final long defaultPollIntervalMs;

    private final Map<String, ConfigSource> sources = new ConcurrentHashMap<>();

    public ConfigSourceResolver(ConfigFormatParser parser, MarzRegistry registry,
                                 SourceStrategyResolver strategyResolver,
                                 String defaultSource, long defaultPollIntervalMs) {
        this.parser = parser;
        this.registry = registry;
        this.strategyResolver = strategyResolver;
        this.defaultSource = defaultSource;
        this.defaultPollIntervalMs = Math.max(500L, defaultPollIntervalMs);
    }

    /**
     * Resolve or create a ConfigSource for the given URI.
     * If the URI is {@code platform://marz} and a defaultSource is configured,
     * the defaultSource is used instead.
     */
    public ConfigSource resolve(String sourceUri) {
        if (sourceUri == null || sourceUri.isEmpty()) return null;

        // Platform fallback: use defaultSource if configured
        if ("platform".equals(extractScheme(sourceUri)) && defaultSource != null && !defaultSource.isEmpty()) {
            log.debug("Resolving platform source '{}' via defaultSource '{}'", sourceUri, defaultSource);
            return resolve(defaultSource);
        }

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
            case "file" -> createFileSource(uri);
            case "classpath" -> createClasspathSource(uri);
            case "http", "https" -> createHttpSource(uri);
            case "platform" -> {
                log.warn("Platform source '{}' not available — set marz.default-source or connect to MARZ Platform.", uri);
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

    /**
     * KAN-27: Resolve classpath: URIs via ClassLoader instead of filesystem Path.
     * Works in both exploded directories and JAR files (read-once for JARs).
     */
    private ConfigSource createClasspathSource(String uri) {
        String resourcePath = uri;
        if (resourcePath.startsWith("classpath:")) {
            resourcePath = resourcePath.substring("classpath:".length());
        }
        // Strip leading slashes for ClassLoader compatibility
        while (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }

        URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
        if (resourceUrl == null) {
            log.warn("Classpath resource not found: {} (resolved from '{}')", resourcePath, uri);
            return null;
        }

        if ("file".equals(resourceUrl.getProtocol())) {
            // Resource is on filesystem (exploded classdir) — use FileConfigSource with WatchService
            try {
                Path filePath = Paths.get(resourceUrl.toURI());
                String fileUri = "file://" + filePath.toAbsolutePath();
                log.debug("Classpath '{}' resolved to filesystem: {}", uri, fileUri);
                return createFileSource(fileUri);
            } catch (Exception e) {
                log.error("Failed to resolve classpath URI '{}' to filesystem: {}", uri, e.getMessage());
                return null;
            }
        } else {
            // Resource is inside a JAR — read once, no watching possible
            log.info("Classpath resource '{}' is inside a JAR (protocol: {}). " +
                    "Initial values will load but changes require restart.", uri, resourceUrl.getProtocol());
            try (var is = resourceUrl.openStream()) {
                String content = new String(is.readAllBytes());
                Map<String, String> parsed = parser.parse(content, uri);
                return new StaticConfigSource(uri, parsed);
            } catch (Exception e) {
                log.error("Failed to read classpath resource '{}': {}", uri, e.getMessage());
                return null;
            }
        }
    }

    private ConfigSource createHttpSource(String uri) {
        long pollSeconds = defaultPollIntervalMs / 1000;
        return new HttpConfigSource(uri, parser, registry, Math.max(1, pollSeconds));
    }

    static String extractScheme(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        int colonIndex = uri.indexOf(':');
        return colonIndex <= 0 ? uri.toLowerCase() : uri.substring(0, colonIndex).toLowerCase();
    }

    /**
     * Read-only config source for JAR-internal classpath resources.
     * Loads once at creation — no change detection (can't watch inside JARs).
     */
    private static class StaticConfigSource implements ConfigSource {
        private final String uri;
        private final Map<String, String> values;

        StaticConfigSource(String uri, Map<String, String> values) {
            this.uri = uri;
            this.values = Map.copyOf(values);
        }

        @Override public String resolve(String key) { return values.get(key); }
        @Override public boolean isAvailable() { return true; }
        @Override public String sourceId() { return uri; }
        @Override public String scheme() { return "classpath"; }
    }
}
