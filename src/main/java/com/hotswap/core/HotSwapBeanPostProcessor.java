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
 * Scans beans for {@code @HotSwap} annotations and registers
 * {@link FieldBinding} entries in the {@link HotSwapRegistry} reverse index.
 *
 * <p>Initial value resolution: config source → defaultValue → field initializer.</p>
 *
 * @since 1.0.0
 */
public class HotSwapBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(HotSwapBeanPostProcessor.class);

    private final HotSwapRegistry registry;
    private final TypeCoercer typeCoercer;
    private final ConfigSourceResolver sourceResolver;

    public HotSwapBeanPostProcessor(HotSwapRegistry registry, TypeCoercer typeCoercer,
                                     ConfigSourceResolver sourceResolver) {
        this.registry = registry;
        this.typeCoercer = typeCoercer;
        this.sourceResolver = sourceResolver;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            HotSwap annotation = field.getAnnotation(HotSwap.class);
            if (annotation != null) processField(bean, field, annotation);
        });
        return bean;
    }

    private void processField(Object bean, Field field, HotSwap annotation) {
        try {
            field.setAccessible(true);
            Object initialValue = resolveInitialValue(bean, field, annotation);
            field.set(bean, initialValue);

            FieldBinding binding = new FieldBinding(bean, bean.getClass().getName(),
                    field.getName(), new AtomicReference<>(initialValue), field.getType(),
                    annotation.key(), annotation.source(), annotation.sensitive());

            registry.register(annotation.key(), binding);
        } catch (Exception e) {
            log.error("Failed to process @HotSwap on {}.{}: {}",
                    bean.getClass().getSimpleName(), field.getName(), e.getMessage(), e);
        }
    }

    private Object resolveInitialValue(Object bean, Field field, HotSwap annotation) {
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
        // 2. defaultValue
        String dv = annotation.defaultValue();
        if (dv != null && !dv.isEmpty()) {
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
