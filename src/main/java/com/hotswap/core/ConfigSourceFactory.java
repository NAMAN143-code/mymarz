package com.hotswap.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory that resolves source URI strings into {@link ConfigSource} instances.
 *
 * <p>Uses the URI scheme (e.g., "file", "classpath", "http") to select
 * the appropriate ConfigSource implementation. Supports both built-in
 * sources and custom sources registered via Spring beans.</p>
 *
 * @since 1.0.0
 */
public class ConfigSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourceFactory.class);

    /** scheme → factory function (URI → ConfigSource) */
    private final Map<String, ConfigSourceCreator> creators = new ConcurrentHashMap<>();

    /** Cache of created sources to avoid duplicates */
    private final Map<String, ConfigSource> sourceCache = new ConcurrentHashMap<>();

    /**
     * Register a creator for a URI scheme.
     *
     * @param scheme  the URI scheme (e.g., "file", "classpath", "http")
     * @param creator a function that creates a ConfigSource from a URI string
     */
    public void registerCreator(String scheme, ConfigSourceCreator creator) {
        creators.put(scheme.toLowerCase(), creator);
        log.debug("Registered ConfigSource creator for scheme: {}", scheme);
    }

    /**
     * Register pre-built ConfigSource instances (e.g., custom Spring beans).
     *
     * @param sources list of ConfigSource beans
     */
    public void registerSources(List<ConfigSource> sources) {
        for (ConfigSource source : sources) {
            creators.put(source.scheme().toLowerCase(), uri -> source);
            log.debug("Registered custom ConfigSource: {} (scheme={})",
                    source.getClass().getSimpleName(), source.scheme());
        }
    }

    /**
     * Create or retrieve a ConfigSource for the given URI.
     *
     * @param sourceUri the full source URI (e.g., "file:///etc/config.yml")
     * @return the ConfigSource, or null if no creator matches the scheme
     */
    public ConfigSource create(String sourceUri) {
        return sourceCache.computeIfAbsent(sourceUri, uri -> {
            String scheme = extractScheme(uri);
            ConfigSourceCreator creator = creators.get(scheme);

            if (creator == null) {
                log.warn("No ConfigSource creator registered for scheme '{}' (URI: {})", scheme, uri);
                return null;
            }

            try {
                ConfigSource source = creator.create(uri);
                log.info("Created ConfigSource for URI '{}': {}", uri,
                        source != null ? source.getClass().getSimpleName() : "null");
                return source;
            } catch (Exception e) {
                log.error("Failed to create ConfigSource for URI '{}': {}", uri, e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Extract the scheme from a URI string.
     * <p>
     * "file:///path" → "file"
     * "classpath:config.yml" → "classpath"
     * "http://host/path" → "http"
     * "platform://hotswap" → "platform"
     */
    static String extractScheme(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        int colonIndex = uri.indexOf(':');
        if (colonIndex <= 0) {
            return uri.toLowerCase();
        }
        return uri.substring(0, colonIndex).toLowerCase();
    }

    /**
     * Functional interface for creating a ConfigSource from a URI.
     */
    @FunctionalInterface
    public interface ConfigSourceCreator {
        ConfigSource create(String uri) throws Exception;
    }
}
