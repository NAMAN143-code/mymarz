package com.hotswap.type;

/**
 * Thrown when a configuration value cannot be coerced to the target field type.
 *
 * @since 1.0.0
 */
public class HotSwapTypeException extends RuntimeException {

    public HotSwapTypeException(String message) {
        super(message);
    }

    public HotSwapTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
