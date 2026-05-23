package com.hotswap.source;

import com.hotswap.core.ConfigSource;
import com.hotswap.core.HotSwapRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Config source backed by an HTTP(S) endpoint (Mode C).
 *
 * <p>Polls using conditional GET with {@code If-None-Match} (ETag).
 * Circuit breaker after 5 consecutive failures with exponential backoff.</p>
 *
 * @since 1.0.0
 */
public class HttpConfigSource implements ConfigSource {

    private static final Logger log = LoggerFactory.getLogger(HttpConfigSource.class);

    public static final long DEFAULT_POLL_INTERVAL_SECONDS = 5;
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    static final long MAX_BACKOFF_MS = 60_000L;
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String uri;
    private final URI endpoint;
    private final ConfigFormatParser parser;
    private final HotSwapRegistry registry;
    private final long pollIntervalSeconds;
    private final HttpClient httpClient;

    private volatile Map<String, String> cachedState = new ConcurrentHashMap<>();
    private volatile String lastEtag;
    private volatile int consecutiveFailures = 0;
    private volatile boolean running = false;

    private ScheduledExecutorService scheduler;

    public HttpConfigSource(String uri, ConfigFormatParser parser,
                            HotSwapRegistry registry, long pollIntervalSeconds) {
        this.uri = uri;
        this.parser = parser;
        this.registry = registry;
        this.pollIntervalSeconds = pollIntervalSeconds > 0 ? pollIntervalSeconds : DEFAULT_POLL_INTERVAL_SECONDS;
        this.endpoint = URI.create(uri);
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        initialLoad();
        log.info("HttpConfigSource created: {} ({} keys, poll every {}s)",
                uri, cachedState.size(), this.pollIntervalSeconds);
    }

    @Override public String resolve(String key) { return cachedState.get(key); }
    @Override public boolean isAvailable() { return !cachedState.isEmpty() || consecutiveFailures < MAX_CONSECUTIVE_FAILURES; }
    @Override public String sourceId() { return uri; }
    @Override public String scheme() { return endpoint.getScheme(); }

    public void start() {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hotswap-http-poll-" + endpoint.getHost());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::poll, pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
        log.info("HttpConfigSource started: polling {} every {}s", uri, pollIntervalSeconds);
    }

    public void stop() {
        running = false;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow();
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("HttpConfigSource stopped: {}", uri);
    }

    public boolean isRunning() { return running; }

    void poll() {
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            long backoff = calculateBackoff(consecutiveFailures);
            long ticksSinceFailure = consecutiveFailures - MAX_CONSECUTIVE_FAILURES;
            long backoffTicks = backoff / (pollIntervalSeconds * 1000);
            if (ticksSinceFailure % Math.max(1, backoffTicks) != 0) return;
        }

        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder().uri(endpoint).timeout(HTTP_TIMEOUT).GET();
            if (lastEtag != null) rb.header("If-None-Match", lastEtag);

            HttpResponse<String> response = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 304) { consecutiveFailures = 0; return; }

            if (status == 200) {
                response.headers().firstValue("ETag").ifPresent(etag -> lastEtag = etag);
                Map<String, String> newState = parser.parse(response.body(), uri);
                Map<String, String> changedKeys = FileConfigSource.diff(cachedState, newState);
                cachedState = new ConcurrentHashMap<>(newState);
                if (!changedKeys.isEmpty()) {
                    log.debug("HTTP poll detected {} changed key(s) from {}", changedKeys.size(), uri);
                    registry.onSourceChange(sourceId(), changedKeys);
                }
                consecutiveFailures = 0;
                return;
            }

            consecutiveFailures++;
            log.warn("HTTP {} from {} (failure #{}/{})", status, uri, consecutiveFailures, MAX_CONSECUTIVE_FAILURES);
        } catch (IOException e) {
            consecutiveFailures++;
            log.warn("HTTP error polling {}: {} (failure #{}/{})", uri, e.getMessage(), consecutiveFailures, MAX_CONSECUTIVE_FAILURES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            consecutiveFailures++;
            log.error("Unexpected error polling {}: {}", uri, e.getMessage(), e);
        }
    }

    long calculateBackoff(int failures) {
        long backoff = (pollIntervalSeconds * 1000) * (long) Math.pow(2, Math.min(failures - MAX_CONSECUTIVE_FAILURES, 10));
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    private void initialLoad() {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(endpoint).timeout(HTTP_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                response.headers().firstValue("ETag").ifPresent(etag -> lastEtag = etag);
                cachedState = new ConcurrentHashMap<>(parser.parse(response.body(), uri));
            }
        } catch (Exception e) {
            log.warn("HttpConfigSource initial load failed for {}: {}", uri, e.getMessage());
        }
    }

    int getConsecutiveFailures() { return consecutiveFailures; }
    Map<String, String> getCachedState() { return Map.copyOf(cachedState); }
    String getLastEtag() { return lastEtag; }
}
