package com.mymarz.core;

import com.mymarz.annotation.Marz;
import com.mymarz.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scans beans for {@code @Marz} annotations and registers
 * {@link FieldBinding} entries in the {@link MarzRegistry} reverse index.
 *
 * <p><strong>Volatile enforcement:</strong> Every {@code @Marz} field MUST be
 * declared {@code volatile}. This is not optional. The hot-swap read path relies
 * on the Java Memory Model's volatile visibility guarantee: when
 * {@link MarzRegistry#onSourceChange} writes a new value via
 * {@code field.set(bean, newValue)}, the volatile write ensures all threads
 * see the updated value on their next read — with ~5ns cost and zero IO.</p>
 *
 * <p>If a non-volatile field is found, the application will fail fast at startup
 * with a clear error message rather than silently swallowing config changes.</p>
 *
 * <p>Initial value resolution: config source → defaultValue → field initializer.</p>
 *
 * @since 1.0.0
 */
public class MarzBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MarzBeanPostProcessor.class);

    private final MarzRegistry registry;
    private final TypeCoercer typeCoercer;
    private final ConfigSourceResolver sourceResolver;
    private final SelfRegistrar selfRegistrar;

    public MarzBeanPostProcessor(MarzRegistry registry, TypeCoercer typeCoercer,
                                     ConfigSourceResolver sourceResolver,
                                     SelfRegistrar selfRegistrar) {
        this.registry = registry;
        this.typeCoercer = typeCoercer;
        this.sourceResolver = sourceResolver;
        this.selfRegistrar = selfRegistrar;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            Marz annotation = field.getAnnotation(Marz.class);
            if (annotation != null) processField(bean, field, annotation);
        });
        return bean;
    }

    private void processField(Object bean, Field field, Marz annotation) {
        try {
            // ── VOLATILE ENFORCEMENT ──────────────────────────────────────
            // The hot-swap read path depends on volatile visibility. Without
            // volatile, Thread A's field.set() from onSourceChange() is NOT
            // guaranteed to be visible to Thread B reading the field directly.
            // Fail fast rather than silently delivering stale values.
            if (!Modifier.isVolatile(field.getModifiers())) {
                throw new IllegalStateException(
                        "@Marz field " + bean.getClass().getSimpleName() + "." + field.getName()
                        + " MUST be declared volatile. "
                        + "The hot-swap mechanism writes new values via reflection from a background thread; "
                        + "without volatile, the Java Memory Model does not guarantee other threads "
                        + "will see the updated value. Fix: change to 'private volatile "
                        + field.getType().getSimpleName() + " " + field.getName() + "'");
            }

            // ── STATIC/FINAL REJECTION ────────────────────────────────────
            if (Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException(
                        "@Marz field " + bean.getClass().getSimpleName() + "." + field.getName()
                        + " must not be static. MARZ binds to bean instances, not class-level state.");
            }
            if (Modifier.isFinal(field.getModifiers())) {
                throw new IllegalStateException(
                        "@Marz field " + bean.getClass().getSimpleName() + "." + field.getName()
                        + " must not be final. MARZ needs to write new values via reflection.");
            }

            // ── POLL INTERVAL VALIDATION (ADR-001 §4) ──────────────────
            long safetyNetInterval = annotation.safetyNetInterval();
            if (safetyNetInterval > 0 && safetyNetInterval < 500) {
                log.warn("@Marz field {}.{} has safetyNetInterval={}ms which is below the 500ms minimum. "
                        + "Clamped to 500ms to prevent polling storms.",
                        bean.getClass().getSimpleName(), field.getName(), safetyNetInterval);
            }

            field.setAccessible(true);
            Object initialValue = resolveInitialValue(bean, field, annotation);
            field.set(bean, initialValue);

            FieldBinding binding = new FieldBinding(bean, bean.getClass().getName(),
                    field.getName(), field, new AtomicReference<>(initialValue), field.getType(),
                    annotation.key(), annotation.source(), annotation.sensitive());

            registry.register(annotation.key(), binding);
        } catch (IllegalStateException e) {
            throw e; // Fail-fast: volatile/static/final violations must crash the app
        } catch (Exception e) {
            log.error("Failed to process @Marz on {}.{}: {}",
                    bean.getClass().getSimpleName(), field.getName(), e.getMessage(), e);
        }
    }

    private Object resolveInitialValue(Object bean, Field field, Marz annotation) {
        // 1. Config source
        if (sourceResolver != null) {
            try {
                ConfigSource source = sourceResolver.resolve(annotation.source());
                if (source != null) {
                    String raw = source.resolve(annotation.key());
                    if (raw != null) return typeCoercer.coerce(raw, field.getType());
                }
            } catch (Exception e) {
                log.warn("Failed to resolve key '{}' from '{}': {}", annotation.key(), annotation.source(), e.getMessage());
            }
        }
        // 2. defaultValue — key was missing from source, record it
        String dv = annotation.defaultValue();
        if (dv != null && !dv.isEmpty()) {
            if (selfRegistrar != null) {
                selfRegistrar.recordMissing(annotation.key(), dv, annotation.source());
            }
            try { return typeCoercer.coerce(dv, field.getType()); }
            catch (Exception e) { log.warn("Failed to coerce defaultValue '{}': {}", dv, e.getMessage()); }
        }
        // 3. Field initializer
        try { return field.get(bean); }
        catch (Exception e) { return getTypeDefault(field.getType()); }
    }

    private Object getTypeDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        return null;
    }
}
