package com.mymarz.source;

import com.mymarz.core.ConfigSource;
import com.mymarz.core.MarzRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Config source backed by an HTTP(S) endpoint (Mode C).
 *
 * <p>Polls using conditional GET with {@code If-None-Match} (ETag).
 * Circuit breaker with {@code nextRetryTime} timestamp pattern for
 * clean exponential backoff.</p>
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
    private final MarzRegistry registry;
    private final long httpPollIntervalSeconds;
    private final HttpClient httpClient;

    private volatile Map<String, String> cachedState = Map.of();
    private volatile String lastEtag;
    private volatile int consecutiveFailures = 0;
    private volatile long nextRetryTime = 0;
    private volatile boolean running = false;

    private ScheduledExecutorService scheduler;

    public HttpConfigSource(String uri, ConfigFormatParser parser,
                            MarzRegistry registry, long httpPollIntervalSeconds) {
        this.uri = uri;
        this.parser = parser;
        this.registry = registry;
        this.httpPollIntervalSeconds = httpPollIntervalSeconds > 0 ? httpPollIntervalSeconds : DEFAULT_POLL_INTERVAL_SECONDS;
        this.endpoint = URI.create(uri);
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        initialLoad();
        log.info("HttpConfigSource created: {} ({} keys, poll every {}s)",
                uri, cachedState.size(), this.httpPollIntervalSeconds);
    }

    @Override public String resolve(String key) { return cachedState.get(key); }
    @Override public boolean isAvailable() { return !cachedState.isEmpty() || consecutiveFailures < MAX_CONSECUTIVE_FAILURES; }
    @Override public String sourceId() { return uri; }
    @Override public String scheme() { return endpoint.getScheme(); }

    @Override
    public void start() {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "marz-http-poll-" + endpoint.getHost());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::poll, httpPollIntervalSeconds, httpPollIntervalSeconds, TimeUnit.SECONDS);
        log.info("HttpConfigSource started: polling {} every {}s", uri, httpPollIntervalSeconds);
    }

    @Override
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

    @Override
    public boolean isRunning() { return running; }

    void poll() {
        // Circuit breaker: skip if we're in backoff period
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            if (System.currentTimeMillis() < nextRetryTime) {
                return; // Still in backoff — skip this tick
            }
            log.debug("Circuit breaker retry for {} (failure #{})", uri, consecutiveFailures);
        }

        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder().uri(endpoint).timeout(HTTP_TIMEOUT).GET();
            if (lastEtag != null) rb.header("If-None-Match", lastEtag);

            HttpResponse<String> response = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 304) { resetFailures(); return; }

            if (status == 200) {
                response.headers().firstValue("ETag").ifPresent(etag -> lastEtag = etag);
                Map<String, String> newState = parser.parse(response.body(), uri);
                Map<String, String> previousState = cachedState;
                Map<String, String> changedKeys = FileConfigSource.diff(previousState, newState);
                if (!changedKeys.isEmpty()) {
                    log.debug("HTTP poll detected {} changed key(s) from {}", changedKeys.size(), uri);
                    // KAN-98: per-key isolation; retain failed keys for retry.
                    Set<String> failed = registry.onSourceChange(sourceId(), changedKeys);
                    cachedState = FileConfigSource.advanceCache(previousState, newState, failed);
                    if (!failed.isEmpty()) {
                        log.warn("HTTP source {}: {} key(s) failed to apply, will retry on next poll: {}",
                                uri, failed.size(), failed);
                        // Drop the ETag so the next tick does a full GET (not a 304) and
                        // re-diffs the retained failed keys instead of stalling on them.
                        lastEtag = null;
                    }
                } else {
                    cachedState = Map.copyOf(newState);
                }
                resetFailures();
                return;
            }

            recordFailure();
            log.warn("HTTP {} from {} (failure #{}/{})", status, uri, consecutiveFailures, MAX_CONSECUTIVE_FAILURES);
        } catch (IOException e) {
            recordFailure();
            log.warn("HTTP error polling {}: {} (failure #{}/{})", uri, e.getMessage(), consecutiveFailures, MAX_CONSECUTIVE_FAILURES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            recordFailure();
            log.error("Unexpected error polling {}: {}", uri, e.getMessage(), e);
        }
    }

    private void resetFailures() {
        consecutiveFailures = 0;
        nextRetryTime = 0;
    }

    private void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            long backoff = calculateBackoff(consecutiveFailures);
            nextRetryTime = System.currentTimeMillis() + backoff;
            log.debug("Circuit breaker: next retry for {} at +{}ms", uri, backoff);
        }
    }

    /**
     * Exponential backoff: 5s, 10s, 20s, 40s, capped at 60s.
     */
    long calculateBackoff(int failures) {
        int exponent = Math.min(failures - MAX_CONSECUTIVE_FAILURES, 10);
        long backoff = (httpPollIntervalSeconds * 1000) * (1L << exponent);
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    private void initialLoad() {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(endpoint).timeout(HTTP_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                response.headers().firstValue("ETag").ifPresent(etag -> lastEtag = etag);
                cachedState = Map.copyOf(parser.parse(response.body(), uri));
            }
        } catch (Exception e) {
            log.warn("HttpConfigSource initial load failed for {}: {}", uri, e.getMessage());
        }
    }

    int getConsecutiveFailures() { return consecutiveFailures; }
    long getNextRetryTime() { return nextRetryTime; }
    Map<String, String> getCachedState() { return cachedState; }
    String getLastEtag() { return lastEtag; }
}
