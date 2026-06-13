package com.mymarz.core;

import com.mymarz.annotation.Marz;
import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * KAN-96: {@link MarzBeanPostProcessor} must unwrap AOP proxies and bind the real
 * target instance — otherwise initial values never apply and hot-swaps are silently
 * invisible on the most common enterprise bean shape ({@code @Transactional}/
 * {@code @Cacheable}/{@code @Async}).
 *
 * <p>Uses Spring's {@link ProxyFactory} to deterministically build CGLIB and JDK
 * proxies (no aspectj/transaction infrastructure needed) and asserts that the
 * binding targets the underlying instance and that a swap is visible through a
 * method invoked on the proxy.</p>
 */
class MarzAopProxyTest {

    private MarzRegistry registry;
    private MarzBeanPostProcessor bpp;

    @BeforeEach
    void setUp() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TypeCoercer coercer = new TypeCoercer();
        registry = new MarzRegistry(publisher, coercer);
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(), null, 5000L);
        bpp = new MarzBeanPostProcessor(registry, coercer, resolver, new SelfRegistrar());
    }

    public interface FeatureService {
        boolean isEnabled();
    }

    public static class FeatureServiceImpl implements FeatureService {
        @Marz(key = "feature.enabled", defaultValue = "false")
        volatile boolean enabled;

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }

    @Test
    @DisplayName("CGLIB proxy: binds the target; initial value applies and a swap is visible through the proxy")
    void cglibProxy_bindsTarget_andSwapVisibleThroughProxy() {
        FeatureServiceImpl target = new FeatureServiceImpl();
        ProxyFactory pf = new ProxyFactory(target);
        pf.setProxyTargetClass(true); // force CGLIB
        FeatureServiceImpl proxy = (FeatureServiceImpl) pf.getProxy();
        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();

        bpp.postProcessAfterInitialization(proxy, "featureService");

        // Registered exactly once, bound to the TARGET — never the proxy.
        assertThat(registry.getBindings("feature.enabled")).hasSize(1);
        assertThat(registry.getBindings("feature.enabled").get(0).bean()).isSameAs(target);

        // Initial value applied to the target.
        assertThat(target.isEnabled()).isFalse();

        // Swap is visible both on the target and through a method invoked on the proxy.
        registry.onSourceChange("file:///c.yml", Map.of("feature.enabled", "true"));
        assertThat(target.enabled).isTrue();
        assertThat(((FeatureService) proxy).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("JDK interface proxy: discovers fields on the target and binds it; swap visible through the proxy")
    void jdkProxy_bindsTarget_andSwapVisibleThroughProxy() {
        FeatureServiceImpl target = new FeatureServiceImpl();
        ProxyFactory pf = new ProxyFactory();
        pf.setTarget(target);
        pf.setInterfaces(FeatureService.class); // force JDK dynamic proxy
        FeatureService proxy = (FeatureService) pf.getProxy();
        assertThat(AopUtils.isJdkDynamicProxy(proxy)).isTrue();

        bpp.postProcessAfterInitialization(proxy, "featureService");

        assertThat(registry.getBindings("feature.enabled")).hasSize(1);
        assertThat(registry.getBindings("feature.enabled").get(0).bean()).isSameAs(target);

        registry.onSourceChange("file:///c.yml", Map.of("feature.enabled", "true"));
        assertThat(proxy.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("proxy with an unreachable (non-singleton) target carrying @Marz fields fails loud — never silent")
    void unreachableProxyTarget_withMarzFields_failsLoud() {
        // A non-static TargetSource yields a new instance per call, so there is no single
        // target to bind — AopProxyUtils.getSingletonTarget returns null. The @Marz field
        // must NOT be silently skipped (KAN-96 AC: "never silent non-registration").
        TargetSource prototypeSource = new TargetSource() {
            @Override public Class<?> getTargetClass() { return FeatureServiceImpl.class; }
            @Override public boolean isStatic() { return false; }
            @Override public Object getTarget() { return new FeatureServiceImpl(); }
            @Override public void releaseTarget(Object target) { }
        };
        ProxyFactory pf = new ProxyFactory();
        pf.setTargetSource(prototypeSource);
        pf.setProxyTargetClass(true);
        Object proxy = pf.getProxy();
        assertThat(AopProxyUtils.getSingletonTarget(proxy)).isNull(); // precondition: unreachable target

        assertThatThrownBy(() -> bpp.postProcessAfterInitialization(proxy, "unreachableService"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be reached");
        assertThat(registry.getBindings("feature.enabled")).isEmpty(); // never silently registered
    }

    @Test
    @DisplayName("regression: a non-proxied bean still binds directly to itself")
    void plainBean_unchanged() {
        FeatureServiceImpl bean = new FeatureServiceImpl();

        bpp.postProcessAfterInitialization(bean, "featureService");

        assertThat(registry.getBindings("feature.enabled")).hasSize(1);
        assertThat(registry.getBindings("feature.enabled").get(0).bean()).isSameAs(bean);
    }
}
