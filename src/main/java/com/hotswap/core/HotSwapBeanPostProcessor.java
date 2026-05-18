package com.hotswap.core;

import com.hotswap.annotation.HotSwap;
import com.hotswap.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

/**
 * Spring {@link BeanPostProcessor} that scans beans for {@code @HotSwap}
 * annotations and registers them with the {@link HotSwapRegistry}.
 *
 * <p>Per ADR-001 lifecycle Phase 1 (INIT): on each bean initialization,
 * scan all declared fields for the {@code @HotSwap} annotation. For each
 * found, resolve the initial value from the config source (or use the
 * default), create an {@link HotSwapFieldHolder}, and register it.</p>
 *
 * @since 1.0.0
 */
public class HotSwapBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(HotSwapBeanPostProcessor.class);

    private final HotSwapRegistry registry;
    private final ConfigSourceFactory sourceFactory;
    private final TypeCoercer typeCoercer;

    public HotSwapBeanPostProcessor(HotSwapRegistry registry,
                                     ConfigSourceFactory sourceFactory,
                                     TypeCoercer typeCoercer) {
        this.registry = registry;
        this.sourceFactory = sourceFactory;
        this.typeCoercer = typeCoercer;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = bean.getClass();

        ReflectionUtils.doWithFields(targetClass, field -> {
            HotSwap annotation = field.getAnnotation(HotSwap.class);
            if (annotation != null) {
                processField(bean, beanName, field, annotation);
            }
        });

        return bean;
    }

    private void processField(Object bean, String beanName, Field field, HotSwap annotation) {
        try {
            field.setAccessible(true);

            // Validate poll interval
            if (annotation.pollInterval() != -1 && annotation.pollInterval() < 500) {
                log.warn("@HotSwap field {}.{}: pollInterval {}ms is below minimum (500ms), using 500ms",
                        bean.getClass().getSimpleName(), field.getName(), annotation.pollInterval());
            }

            // Resolve or create the ConfigSource for this source URI
            String sourceUri = annotation.source();
            ConfigSource configSource = registry.getConfigSource(sourceUri);
            if (configSource == null) {
                configSource = sourceFactory.create(sourceUri);
                if (configSource != null) {
                    registry.registerSource(sourceUri, configSource);
                } else {
                    log.warn("No ConfigSource available for URI '{}' — field {}.{} will use default value",
                            sourceUri, bean.getClass().getSimpleName(), field.getName());
                }
            }

            // Resolve initial value
            Object initialValue = resolveInitialValue(field, annotation, configSource);

            // Write initial value to the field
            field.set(bean, initialValue);

            // Create and register the field holder
            HotSwapFieldHolder holder = new HotSwapFieldHolder(bean, field, annotation, initialValue);
            registry.register(holder);

            log.debug("Processed @HotSwap field: {}.{} key='{}' initialValue={}",
                    bean.getClass().getSimpleName(), field.getName(),
                    annotation.key(), initialValue);

        } catch (Exception e) {
            log.error("Failed to process @HotSwap annotation on {}.{}: {}",
                    bean.getClass().getSimpleName(), field.getName(), e.getMessage(), e);
        }
    }

    /**
     * Resolve the initial value: try source first, fall back to defaultValue,
     * then fall back to the field's existing value.
     */
    private Object resolveInitialValue(Field field, HotSwap annotation, ConfigSource configSource) {
        // Try resolving from source
        if (configSource != null) {
            try {
                String rawValue = configSource.resolve(annotation.key());
                if (rawValue != null) {
                    return typeCoercer.coerce(rawValue, annotation.type(), field);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve initial value for key '{}' from source '{}': {}",
                        annotation.key(), annotation.source(), e.getMessage());
            }
        }

        // Try defaultValue
        String defaultValue = annotation.defaultValue();
        if (defaultValue != null && !defaultValue.isEmpty()) {
            try {
                return typeCoercer.coerce(defaultValue, annotation.type(), field);
            } catch (Exception e) {
                log.warn("Failed to coerce defaultValue '{}' for key '{}': {}",
                        defaultValue, annotation.key(), e.getMessage());
            }
        }

        // Fall back to the field's existing value (the Java initializer value)
        try {
            return field.get(null); // Will fail for instance fields
        } catch (Exception e) {
            return getTypeDefault(field.getType());
        }
    }

    /**
     * Get the JVM default value for a primitive type.
     */
    private Object getTypeDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        return null;
    }
}
