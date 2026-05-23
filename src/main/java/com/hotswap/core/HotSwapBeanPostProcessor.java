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
 * <p>Per ADR-001 Amendment: on each bean initialization, scan all declared
 * fields for the {@code @HotSwap} annotation. For each found, resolve the
 * initial value, create an immutable {@link FieldBinding}, and register it
 * in the reverse index.</p>
 *
 * @since 1.0.0
 */
public class HotSwapBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(HotSwapBeanPostProcessor.class);

    private final HotSwapRegistry registry;
    private final TypeCoercer typeCoercer;

    public HotSwapBeanPostProcessor(HotSwapRegistry registry, TypeCoercer typeCoercer) {
        this.registry = registry;
        this.typeCoercer = typeCoercer;
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

            // Resolve initial value from field's Java initializer or defaultValue
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
     * Resolve the initial value: try defaultValue annotation attribute,
     * then fall back to the field's existing Java initializer value.
     */
    private Object resolveInitialValue(Object bean, Field field, HotSwap annotation) {
        // Try defaultValue from annotation
        String defaultValue = annotation.defaultValue();
        if (defaultValue != null && !defaultValue.isEmpty()) {
            try {
                return typeCoercer.coerce(defaultValue, field.getType());
            } catch (Exception e) {
                log.warn("Failed to coerce defaultValue '{}' for key '{}': {}",
                        defaultValue, annotation.key(), e.getMessage());
            }
        }

        // Fall back to the field's existing value (Java initializer)
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
