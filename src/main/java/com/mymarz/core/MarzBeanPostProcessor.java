package com.mymarz.core;

import com.mymarz.annotation.Marz;
import com.mymarz.type.TypeCoercer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scans beans for {@code @Marz} annotations and registers
 * {@link FieldBinding} entries in the {@link MarzRegistry} reverse index.
 *
 * <p><strong>AOP proxy awareness (KAN-96):</strong> Spring wraps
 * {@code @Transactional}/{@code @Cacheable}/{@code @Async} beans in a proxy. If MARZ
 * bound the proxy, it would write the proxy's inherited field copy while business
 * methods execute on the <em>target</em> instance — initial values would never apply
 * and hot-swaps would be silently invisible (CGLIB), or the {@code @Marz} field would
 * never be discovered at all (JDK interface proxy). This processor therefore unwraps
 * to the real target via {@link AopProxyUtils#getSingletonTarget} and binds the target,
 * never the proxy. A proxy whose target cannot be reached is never silently skipped — if
 * it carries {@code @Marz} fields the application fails fast.</p>
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
 * <p><strong>Singleton-scope enforcement (KAN-99):</strong> {@code @Marz} is only
 * supported on singleton beans in this release. A non-singleton (prototype/request/
 * session) instance would be pinned in the registry forever — an unbounded memory
 * leak — so it is rejected fast with an actionable message. Bindings for destroyed
 * singletons are unregistered via {@link DestructionAwareBeanPostProcessor}.</p>
 *
 * <p>Initial value resolution: config source → defaultValue → field initializer.</p>
 *
 * @since 1.0.0
 */
public class MarzBeanPostProcessor implements DestructionAwareBeanPostProcessor, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(MarzBeanPostProcessor.class);

    private final MarzRegistry registry;
    private final TypeCoercer typeCoercer;
    private final ConfigSourceResolver sourceResolver;
    private final SelfRegistrar selfRegistrar;

    /** Set via {@link BeanFactoryAware}; enables scope detection. May be null in unit tests. */
    private ConfigurableListableBeanFactory beanFactory;

    public MarzBeanPostProcessor(MarzRegistry registry, TypeCoercer typeCoercer,
                                     ConfigSourceResolver sourceResolver,
                                     SelfRegistrar selfRegistrar) {
        this.registry = registry;
        this.typeCoercer = typeCoercer;
        this.sourceResolver = sourceResolver;
        this.selfRegistrar = selfRegistrar;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof ConfigurableListableBeanFactory clbf) {
            this.beanFactory = clbf;
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // KAN-96: resolve the real target, unwrapping any AOP proxy.
        Object target = resolveTarget(bean, beanName);
        if (target == null) {
            return bean; // proxy with unreachable target and no @Marz fields — nothing to bind
        }

        // KAN-99: @Marz is only supported on singletons in this release.
        rejectIfNonSingleton(target, beanName);

        final Object effectiveTarget = target;
        ReflectionUtils.doWithFields(effectiveTarget.getClass(), field -> {
            Marz annotation = field.getAnnotation(Marz.class);
            if (annotation != null) processField(effectiveTarget, field, annotation);
        });
        // Always return the ORIGINAL bean (the proxy, if any) so Spring's wiring is intact.
        return bean;
    }

    @Override
    public void postProcessBeforeDestruction(Object bean, String beanName) throws BeansException {
        // Unregister the same instance we bound (the unwrapped target) so its
        // FieldBinding no longer pins it — KAN-99 leak fix for dynamic/child contexts.
        Object singletonTarget = AopProxyUtils.getSingletonTarget(bean);
        registry.unregister(singletonTarget != null ? singletonTarget : bean);
    }

    @Override
    public boolean requiresDestruction(Object bean) {
        // Cheap: unregister is a no-op when no bindings reference the bean.
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROXY UNWRAP (KAN-96)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Resolve the real instance to bind, unwrapping AOP proxies.
     *
     * @return the target instance to bind; or {@code null} when the bean is a proxy
     *         whose singleton target cannot be reached AND it carries no {@code @Marz}
     *         fields (nothing to do)
     * @throws IllegalStateException if such an unreachable-target proxy <em>does</em>
     *         carry {@code @Marz} fields — those must never be silently skipped
     */
    private Object resolveTarget(Object bean, String beanName) {
        if (!AopUtils.isAopProxy(bean)) {
            // Plain bean. MARZ may also run before the auto-proxy creator, in which
            // case binding the raw target is exactly right.
            return bean;
        }

        Object singletonTarget = AopProxyUtils.getSingletonTarget(bean);
        if (singletonTarget != null) {
            return singletonTarget; // CGLIB or JDK proxy over a singleton target
        }

        // Proxy whose backing instance we cannot reach (scoped proxy, ThreadLocal or
        // other custom TargetSource). Discover fields on the target class; if any are
        // @Marz, fail loud — never silent non-registration.
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Field offending = firstMarzField(targetClass);
        if (offending != null) {
            throw new IllegalStateException(
                    "@Marz field " + targetClass.getSimpleName() + "." + offending.getName()
                    + " is declared on AOP-proxied bean '" + beanName + "' whose target instance "
                    + "cannot be reached (non-singleton scope or a custom TargetSource). MARZ cannot "
                    + "bind through such a proxy without losing hot-swap visibility. Fix: make the bean "
                    + "a singleton, or move the @Marz field onto a singleton collaborator.");
        }
        return null;
    }

    /** @return the first {@code @Marz}-annotated field on {@code type} (incl. superclasses), or null. */
    private static Field firstMarzField(Class<?> type) {
        Field[] holder = new Field[1];
        ReflectionUtils.doWithFields(type, f -> {
            if (holder[0] == null && f.isAnnotationPresent(Marz.class)) holder[0] = f;
        });
        return holder[0];
    }

    // ═══════════════════════════════════════════════════════════════════
    // SCOPE ENFORCEMENT (KAN-99)
    // ═══════════════════════════════════════════════════════════════════

    private void rejectIfNonSingleton(Object target, String beanName) {
        if (beanFactory == null || beanName == null) {
            return; // cannot determine scope (manual registration / unit test) — allow
        }
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return; // inner/anonymous/framework bean with no public definition — cannot determine
        }
        BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
        if (bd.isSingleton()) {
            return; // the supported case
        }
        // Only fail when the bean actually carries @Marz fields.
        if (firstMarzField(target.getClass()) == null) {
            return;
        }
        String scope = (bd.getScope() == null || bd.getScope().isEmpty()) ? "prototype" : bd.getScope();
        throw new IllegalStateException(
                "@Marz is only supported on singleton-scoped beans, but bean '" + beanName
                + "' (" + target.getClass().getName() + ") has scope '" + scope + "'. Every non-singleton "
                + "instance would be pinned in the registry forever (memory leak) and the swap loop would "
                + "iterate dead instances. Fix: make this bean a singleton, or move the @Marz field onto a "
                + "singleton bean. (Weak-reference support for other scopes is planned for a future release.)");
    }

    // ═══════════════════════════════════════════════════════════════════
    // FIELD PROCESSING
    // ═══════════════════════════════════════════════════════════════════

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
                    annotation.key(), annotation.source(), annotation.sensitive(), annotation.defaultValue());

            registry.register(annotation.key(), binding);
        } catch (IllegalStateException e) {
            throw e; // Fail-fast: volatile/static/final/scope violations must crash the app
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
