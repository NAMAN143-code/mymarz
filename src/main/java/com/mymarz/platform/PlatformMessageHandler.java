package com.mymarz.platform;

/**
 * Handler for messages received from the MARZ Platform via WebSocket.
 *
 * <p>Implementations process specific message types (CONFIG_UPDATE,
 * CONFIG_SYNC, etc.) and return responses to send back.</p>
 *
 * @since 1.0.0
 */
public interface PlatformMessageHandler {

    /**
     * Handle a text message from the platform.
     *
     * @param message the raw JSON message
     * @return response to send back, or {@code null} if no response needed
     */
    String onMessage(String message);

    /**
     * Called when the WebSocket connection is established or re-established.
     * Implementations should send REGISTER payload here.
     */
    void onConnect();

    /**
     * Called when the WebSocket connection is lost.
     */
    void onDisconnect();
}
