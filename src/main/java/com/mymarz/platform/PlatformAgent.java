package com.mymarz.platform;

import com.mymarz.autoconfigure.MarzProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outbound WebSocket client connecting to the MARZ Platform.
 *
 * <p>Only activates when {@code marz.platform.api-key} is configured.
 * Manages the WebSocket lifecycle: connect, auto-reconnect with exponential
 * backoff, graceful shutdown. All WebSocket operations run on a dedicated
 * daemon thread — never blocks application threads.</p>
 *
 * <p>Per ADR-004: the agent is resilient to platform unavailability. If the
 * platform is unreachable at startup, the agent retries in the background.
 * The application starts normally with file-based config.</p>
 *
 * @since 1.0.0
 */
public class PlatformAgent {

    private static final Logger log = LoggerFactory.getLogger(PlatformAgent.class);

    private static final long INITIAL_BACKOFF_MS = 5_000L;

    private final MarzProperties.Platform config;
    private final List<PlatformMessageHandler> handlers;
    private final HttpClient httpClient;

    private final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
    private final AtomicReference<ConnectionState> stateRef = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private volatile boolean shutdownRequested = false;
    private ScheduledExecutorService reconnectScheduler;

    /**
     * Connection states for health reporting.
     */
    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

    public PlatformAgent(MarzProperties.Platform config, List<PlatformMessageHandler> handlers) {
        this.config = config;
        this.handlers = handlers != null ? handlers : List.of();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    /**
     * Start the agent — connect to the platform asynchronously.
     * Called by SmartLifecycle after all beans are initialized.
     */
    public void start() {
        if (shutdownRequested) return;

        reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "marz-platform-agent");
            t.setDaemon(true);
            return t;
        });

        log.info("MARZ Platform Agent starting — connecting to {}", config.getEndpoint());
        connectAsync();
    }

    /**
     * Graceful shutdown — close WebSocket and stop reconnect scheduler.
     */
    public void stop() {
        shutdownRequested = true;
        stateRef.set(ConnectionState.DISCONNECTED);

        WebSocket ws = webSocketRef.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Application shutting down")
                        .orTimeout(2, TimeUnit.SECONDS)
                        .join();
            } catch (Exception e) {
                log.debug("WebSocket close interrupted: {}", e.getMessage());
            }
        }

        if (reconnectScheduler != null && !reconnectScheduler.isShutdown()) {
            reconnectScheduler.shutdown();
        }

        log.info("MARZ Platform Agent stopped");
    }

    /**
     * Send a text message to the platform.
     *
     * @param message JSON text to send
     * @return true if sent successfully, false if not connected
     */
    public boolean send(String message) {
        WebSocket ws = webSocketRef.get();
        if (ws == null || stateRef.get() != ConnectionState.CONNECTED) {
            log.debug("Cannot send — not connected to platform");
            return false;
        }

        try {
            ws.sendText(message, true).toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            return true;
        } catch (Exception e) {
            log.warn("Failed to send message to platform: {}", e.getMessage());
            return false;
        }
    }

    /**
     * @return current connection state
     */
    public ConnectionState getState() {
        return stateRef.get();
    }

    /**
     * @return true if connected to the platform
     */
    public boolean isConnected() {
        return stateRef.get() == ConnectionState.CONNECTED;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONNECTION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    private void connectAsync() {
        if (shutdownRequested) return;

        stateRef.set(consecutiveFailures.get() == 0 ? ConnectionState.CONNECTING : ConnectionState.RECONNECTING);

        try {
            URI endpoint = URI.create(config.getEndpoint());

            httpClient.newWebSocketBuilder()
                    .header("X-Marz-Api-Key", config.getApiKey())
                    .header("X-Marz-Agent-Version", "1.0.0")
                    .buildAsync(endpoint, new AgentWebSocketListener())
                    .whenComplete((ws, ex) -> {
                        if (ex != null) {
                            onConnectionFailure(ex);
                        }
                        // Success handled in AgentWebSocketListener.onOpen
                    });

        } catch (Exception e) {
            onConnectionFailure(e);
        }
    }

    private void onConnectionSuccess(WebSocket ws) {
        webSocketRef.set(ws);
        stateRef.set(ConnectionState.CONNECTED);
        consecutiveFailures.set(0);

        log.info("Connected to MARZ Platform at {}", config.getEndpoint());

        // Notify handlers (REGISTER payload, etc.)
        for (PlatformMessageHandler handler : handlers) {
            try {
                handler.onConnect();
            } catch (Exception e) {
                log.error("Handler onConnect failed: {}", e.getMessage(), e);
            }
        }
    }

    private void onConnectionFailure(Throwable ex) {
        int failures = consecutiveFailures.incrementAndGet();
        stateRef.set(ConnectionState.DISCONNECTED);

        long backoff = calculateBackoff(failures);
        log.warn("Platform connection failed (attempt #{}): {}. Retrying in {}ms",
                failures, ex.getMessage(), backoff);

        scheduleReconnect(backoff);
    }

    private void onDisconnect(int statusCode, String reason) {
        webSocketRef.set(null);
        stateRef.set(ConnectionState.DISCONNECTED);

        // Notify handlers
        for (PlatformMessageHandler handler : handlers) {
            try {
                handler.onDisconnect();
            } catch (Exception e) {
                log.error("Handler onDisconnect failed: {}", e.getMessage(), e);
            }
        }

        if (!shutdownRequested && statusCode != WebSocket.NORMAL_CLOSURE) {
            int failures = consecutiveFailures.incrementAndGet();
            long backoff = calculateBackoff(failures);
            log.warn("Platform disconnected (code={}, reason='{}'). Reconnecting in {}ms",
                    statusCode, reason, backoff);
            scheduleReconnect(backoff);
        }
    }

    private void scheduleReconnect(long delayMs) {
        if (shutdownRequested || reconnectScheduler == null || reconnectScheduler.isShutdown()) return;

        reconnectScheduler.schedule(this::connectAsync, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Exponential backoff: 5s → 10s → 20s → 40s → cap at reconnectMaxDelayMs.
     */
    long calculateBackoff(int failures) {
        long backoff = INITIAL_BACKOFF_MS * (1L << Math.min(failures - 1, 10));
        return Math.min(backoff, config.getReconnectMaxDelayMs());
    }

    // ═══════════════════════════════════════════════════════════════════
    // WEBSOCKET LISTENER
    // ═══════════════════════════════════════════════════════════════════

    private class AgentWebSocketListener implements WebSocket.Listener {

        private final StringBuilder messageBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.debug("WebSocket onOpen");
            onConnectionSuccess(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);

            if (last) {
                String fullMessage = messageBuffer.toString();
                messageBuffer.setLength(0);

                // Dispatch to handlers
                for (PlatformMessageHandler handler : handlers) {
                    try {
                        String response = handler.onMessage(fullMessage);
                        if (response != null) {
                            send(response);
                        }
                    } catch (Exception e) {
                        log.error("Handler failed processing message: {}", e.getMessage(), e);
                    }
                }
            }

            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("WebSocket onClose: code={}, reason='{}'", statusCode, reason);
            onDisconnect(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("WebSocket error: {}", error.getMessage());
            onDisconnect(1006, error.getMessage());
        }
    }
}
