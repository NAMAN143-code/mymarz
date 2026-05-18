package com.hotswap.source;

import com.hotswap.core.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ConfigSource} backed by a local file (YAML / JSON / .properties).
 *
 * <p>The file is re-read only when its last-modified timestamp changes,
 * so repeated {@link #resolve} calls within the same poll cycle are O(1)
 * map lookups — no disk I/O.</p>
 *
 * <p>URI format: {@code file:///absolute/path/to/config.yml}</p>
 *
 * @since 1.0.0
 */
public class FileConfigSource implements ConfigSource {

    private static final Logger log = LoggerFactory.getLogger(FileConfigSource.class);

    private final Path filePath;
    private final String uri;
    private final ConfigFormatParser parser;

    private volatile Map<String, String> cachedValues = new ConcurrentHashMap<>();
    private volatile long lastModified = -1L;

    public FileConfigSource(String uri, ConfigFormatParser parser) {
        this.uri    = uri;
        this.parser = parser;
        this.filePath = resolveFilePath(uri);
        log.debug("FileConfigSource created for path: {}", filePath);
    }

    // -------------------------------------------------------------------------
    // ConfigSource contract
    // -------------------------------------------------------------------------

    @Override
    public String resolve(String key) {
        refreshIfModified();
        return cachedValues.get(key);
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

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Re-read the file only if its last-modified timestamp has changed since
     * the previous read. Thread-safe: volatile write ensures visibility.
     */
    void refreshIfModified() {
        if (!Files.exists(filePath)) {
            log.trace("Config file does not exist: {}", filePath);
            return;
        }
        try {
            long currentModified = Files.getLastModifiedTime(filePath).toMillis();
            if (currentModified != lastModified) {
                String content = Files.readString(filePath);
                Map<String, String> parsed = parser.parse(content, uri);
                cachedValues  = new ConcurrentHashMap<>(parsed);
                lastModified  = currentModified;
                log.debug("Config file reloaded: {} ({} keys)", filePath, parsed.size());
            }
        } catch (IOException e) {
            log.error("Error reading config file '{}': {}", filePath, e.getMessage());
        }
    }

    /**
     * Converts a {@code file://} URI string to a {@link Path}.
     * Handles {@code file:///abs/path}, {@code file://rel/path}, {@code file:path}.
     */
    static Path resolveFilePath(String uri) {
        String path = uri;
        if (path.startsWith("file:///")) {
            path = path.substring(7);       // file:///path  →  /path
        } else if (path.startsWith("file://")) {
            path = path.substring(7);       // file://rel    →  rel
        } else if (path.startsWith("file:")) {
            path = path.substring(5);       // file:path     →  path
        }
        return Paths.get(path);
    }

    // package-private for testing
    Map<String, String> getCachedValues() {
        return Collections.unmodifiableMap(cachedValues);
    }

    long getLastModified() {
        return lastModified;
    }
}
