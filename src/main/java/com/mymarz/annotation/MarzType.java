package com.mymarz.annotation;

/**
 * Supported value types for {@link Marz} annotation fields.
 *
 * <p>Defines how raw String values from config sources are coerced
 * into the target field's Java type.</p>
 *
 * @since 1.0.0
 */
public enum MarzType {

    /** Auto-detect type from the annotated field's declared type via reflection. */
    INFERRED,

    /** {@code "true"/"false"} to {@code boolean/Boolean}. */
    BOOLEAN,

    /** Raw string, no coercion. */
    STRING,

    /** Numeric string to {@code int/Integer}. */
    INTEGER,

    /** Numeric string to {@code long/Long}. */
    LONG,

    /** Numeric string to {@code double/Double}. */
    DOUBLE,

    /**
     * JSON string deserialized to the field's declared type via Jackson.
     */
    JSON
}
