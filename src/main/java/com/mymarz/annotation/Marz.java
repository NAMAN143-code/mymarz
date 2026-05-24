package com.mymarz.annotation;

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
 * guaranteed via Java's {@code volatile} keyword — the field <strong>must</strong>
 * be declared {@code volatile}. The application will fail fast at startup
 * if a non-volatile field is annotated with {@code @Marz}.</p>
 *
 * <p><strong>Read path:</strong> Application code reads the volatile field
 * directly (~5ns, zero IO, zero reflection). This is a standard JVM volatile
 * read — the fastest possible cross-thread visibility mechanism.</p>
 *
 * <p><strong>Write path:</strong> When the config source detects a change,
 * the new value is written to the volatile field via reflection from a
 * background thread. The volatile write guarantees immediate visibility
 * to all application threads.</p>
 *
 * <p>Minimal usage:</p>
 * <pre>
 * &#64;Marz(key = "feature.dark-mode.enabled")
 * private volatile boolean darkModeEnabled = false;
 * </pre>
 *
 * <p>Full usage:</p>
 * <pre>
 * &#64;Marz(
 *     key = "rate.limit.max-requests",
 *     source = "file:///etc/myapp/config.yml",
 *     safetyNetInterval = 60000,
 *     defaultValue = "100",
 *     type = MarzType.INTEGER,
 *     description = "Maximum API requests per minute",
 *     requiresApproval = true
 * )
 * private volatile int maxRequests;
 * </pre>
 *
 * @since 1.0.0
 * @see MarzType
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Marz {

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
     * {@code http(s)://}, {@code platform://marz}.</p>
     *
     * @return the source URI (default: platform://marz)
     */
    String source() default "platform://marz";

    /**
     * Safety-net CRC32 check interval in milliseconds. WatchService is the primary
     * event-driven mechanism; this periodic checksum runs as insurance for edge cases
     * (NFS mounts, macOS kqueue delays). Minimum: 500ms.
     * Set to {@code -1} for push-only mode (platform WebSocket, no file polling).
     *
     * @return safety-net interval in ms (default: 60000)
     */
    long safetyNetInterval() default 60_000L;

    /**
     * Default value as a String, coerced to the target field type.
     * Used when the source is unreachable or the key is not found.
     *
     * @return default value string (default: "")
     */
    String defaultValue() default "";

    /**
     * Explicit type hint for value coercion.
     * When {@link MarzType#INFERRED}, type is detected via reflection.
     *
     * @return the type hint (default: INFERRED)
     */
    MarzType type() default MarzType.INFERRED;

    /**
     * Human-readable description shown in the MARZ Platform dashboard.
     *
     * @return description text (default: "")
     */
    String description() default "";

    /**
     * Whether changes require RBAC approval in the platform.
     * Ignored when source is not {@code platform://marz}.
     *
     * @return true if approval is required (default: false)
     */
    boolean requiresApproval() default false;

    /**
     * Whether this config value is sensitive (e.g., secrets, tokens, PII).
     * When {@code true}, values are masked as {@code ***} in all log output,
     * {@link com.mymarz.core.MarzEvent#toString()}, and heartbeat reports.
     *
     * @return true if value should be masked in logs (default: false)
     */
    boolean sensitive() default false;
}
