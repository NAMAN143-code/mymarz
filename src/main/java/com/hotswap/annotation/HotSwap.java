package com.hotswap.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring bean field for runtime hot-swap configuration.
 *
 * <p>The annotated field's value will be dynamically updated from an external
 * configuration source without requiring a JVM restart. Thread-safety is
 * guaranteed via internal {@link java.util.concurrent.atomic.AtomicReference} wrapping.</p>
 *
 * <p>Minimal usage:</p>
 * <pre>
 * &#64;HotSwap(key = "feature.dark-mode.enabled")
 * private boolean darkModeEnabled = false;
 * </pre>
 *
 * <p>Full usage:</p>
 * <pre>
 * &#64;HotSwap(
 *     key = "rate.limit.max-requests",
 *     source = "file:///etc/myapp/config.yml",
 *     pollInterval = 10000,
 *     defaultValue = "100",
 *     type = HotSwapType.INTEGER,
 *     description = "Maximum API requests per minute",
 *     requiresApproval = true
 * )
 * private int maxRequests;
 * </pre>
 *
 * @since 1.0.0
 * @see HotSwapType
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HotSwap {

    /**
     * The configuration key to resolve from the source.
     * Uses dot-notation for hierarchical config structures.
     *
     * @return the configuration key (required)
     */
    String key();

    /**
     * Configuration source URI.
     * <p>Supported schemes: {@code file://}, {@code classpath:},
     * {@code http(s)://}, {@code platform://hotswap}.</p>
     *
     * @return the source URI (default: platform://hotswap)
     */
    String source() default "platform://hotswap";

    /**
     * Polling interval in milliseconds. Minimum: 500ms.
     * Set to {@code -1} to disable polling (push-only mode).
     *
     * @return polling interval in ms (default: 5000)
     */
    long pollInterval() default 5000L;

    /**
     * Default value as a String, coerced to the target field type.
     * Used when the source is unreachable or the key is not found.
     *
     * @return default value string (default: "")
     */
    String defaultValue() default "";

    /**
     * Explicit type hint for value coercion.
     * When {@link HotSwapType#INFERRED}, type is detected via reflection.
     *
     * @return the type hint (default: INFERRED)
     */
    HotSwapType type() default HotSwapType.INFERRED;

    /**
     * Human-readable description shown in the HotSwap Platform dashboard.
     *
     * @return description text (default: "")
     */
    String description() default "";

    /**
     * Whether changes require RBAC approval in the platform.
     * Ignored when source is not {@code platform://hotswap}.
     *
     * @return true if approval is required (default: false)
     */
    boolean requiresApproval() default false;
}
