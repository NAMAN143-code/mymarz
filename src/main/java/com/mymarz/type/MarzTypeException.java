package com.mymarz.type;

/**
 * Thrown when a configuration value cannot be coerced to the target field type.
 *
 * @since 1.0.0
 */
public class MarzTypeException extends RuntimeException {

    public MarzTypeException(String message) {
        super(message);
    }

    public MarzTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
