package com.hotswap.core;

import com.hotswap.annotation.HotSwap;
import com.hotswap.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring {@link BeanPostProcessor} that scans beans for {@code @HotSwap}
 * annotations and registers them as {@link FieldBinding} entries in the
 * {@link HotSwapRegistry} reverse index.
 *
 * <p>Initial value resolution order:</p>
 * <ol>
 *   <li>Config source (via {@link ConfigSourceResolver}) — the source of truth</li>
 *   <li>{@code @HotSwap(defaultValue = "...")} — fallback</li>
 *   <li>Java field initializer value — last resort</li>
 * </ol>
 *
 * @since 1.0.0
 */
public class HotSwapBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(HotSwapBeanPostProcessor.class);

    private final HotSwapRegistry registry;
    private final TypeCoercer typeCoercer;
    private final ConfigSourceResolver sourceResolver;

    public HotSwapBeanPostProcessor(HotSwapRegistry registry,
                                     TypeCoercer typeCoercer,
                                     ConfigSourceResolver sourceResolver) {
        this.registry = registry;
        this.typeCoercer = typeCoercer;
        this.sourceResolver = sourceResolver;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = bean.getClass();

        ReflectionUtils.doWithFields(targetClass, field -> {
            HotSwap annotation = field.getAnnotation(HotSwap.class);
            if (annotation != null) {
                processField(bean, field, annotation);
            }
        });

        return bean;
    }

    private void processField(Object bean, Field field, HotSwap annotation) {
        try {
            field.setAccessible(true);

            // Resolve initial value (source → defaultValue → field initializer)
            Object initialValue = resolveInitialValue(bean, field, annotation);

            // Write initial value to the field
            field.set(bean, initialValue);

            // Create immutable FieldBinding for the reverse index
            FieldBinding binding = new FieldBinding(
                    bean,
                    bean.getClass().getName(),
                    field.getName(),
                    new AtomicReference<>(initialValue),
                    field.getType(),
                    annotation.key(),
                    annotation.source(),
                    annotation.sensitive()
            );

            registry.register(annotation.key(), binding);

        } catch (Exception e) {
            log.error("Failed to process @HotSwap annotation on {}.{}: {}",
                    bean.getClass().getSimpleName(), field.getName(), e.getMessage(), e);
        }
    }

    /**
     * Resolve initial value: try config source first, then defaultValue, then field initializer.
     */
    private Object resolveInitialValue(Object bean, Field field, HotSwap annotation) {
        // 1. Try resolving from the config source
        if (sourceResolver != null) {
            try {
                ConfigSource source = sourceResolver.resolve(annotation.source());
                if (source != null) {
                    String rawValue = source.resolve(annotation.key());
                    if (rawValue != null) {
                        return typeCoercer.coerce(rawValue, field.getType());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to resolve initial value for key '{}' from source '{}': {}",
                        annotation.key(), annotation.source(), e.getMessage());
            }
        }

        // 2. Try defaultValue from annotation
        String defaultValue = annotation.defaultValue();
        if (defaultValue != null && !defaultValue.isEmpty()) {
            try {
                return typeCoercer.coerce(defaultValue, field.getType());
            } catch (Exception e) {
                log.warn("Failed to coerce defaultValue '{}' for key '{}': {}",
                        defaultValue, annotation.key(), e.getMessage());
            }
        }

        // 3. Fall back to the field's existing value (Java initializer)
        try {
            return field.get(bean);
        } catch (Exception e) {
            return getTypeDefault(field.getType());
        }
    }

    private Object getTypeDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        return null;
    }
}
