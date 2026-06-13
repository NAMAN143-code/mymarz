package com.mymarz.core;

import com.mymarz.annotation.Marz;
import com.mymarz.autoconfigure.MarzAutoConfiguration;
import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * KAN-99: the binding registry must not pin beans forever.
 *
 * <ul>
 *   <li>Non-singleton {@code @Marz} beans are rejected fast (they would leak).</li>
 *   <li>Destroyed singletons have their bindings unregistered.</li>
 *   <li>{@link MarzRegistry#unregister} removes only the target instance's bindings.</li>
 * </ul>
 */
class MarzScopeAndDestructionTest {

    static class FlagHolder {
        @Marz(key = "x.flag", defaultValue = "false")
        volatile boolean flag;
    }

    // ═══════════════════════════════════════════════════════════════════
    // registry.unregister — unit
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MarzRegistry.unregister")
    class Unregister {

        private MarzRegistry registry() {
            return new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());
        }

        private FieldBinding bind(MarzRegistry registry, Object bean, String key) {
            AtomicReference<Object> ref = new AtomicReference<>(false);
            FieldBinding b = new FieldBinding(bean, "Holder", "flag", null, ref,
                    boolean.class, key, "file:///c.yml", false, "false");
            registry.register(key, b);
            return b;
        }

        @Test
        @DisplayName("removes only the destroyed bean's binding; the key survives while others remain")
        void removesOnlyTargetInstance() {
            MarzRegistry registry = registry();
            Object beanA = new Object();
            Object beanB = new Object();
            bind(registry, beanA, "shared.key");
            bind(registry, beanB, "shared.key");
            assertThat(registry.getBindings("shared.key")).hasSize(2);

            int removed = registry.unregister(beanA);

            assertThat(removed).isEqualTo(1);
            assertThat(registry.getBindings("shared.key")).hasSize(1);
            assertThat(registry.getBindings("shared.key").get(0).bean()).isSameAs(beanB);
        }

        @Test
        @DisplayName("removes the key entirely once its last binding is gone")
        void removesKeyWhenEmpty() {
            MarzRegistry registry = registry();
            Object bean = new Object();
            bind(registry, bean, "only.key");
            assertThat(registry.getRegisteredKeyCount()).isEqualTo(1);

            registry.unregister(bean);

            assertThat(registry.getBindings("only.key")).isEmpty();
            assertThat(registry.getRegisteredKeyCount()).isZero();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BPP destruction hook — unit
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("postProcessBeforeDestruction unregisters the bean's bindings")
    void destructionHook_unregisters() {
        MarzRegistry registry = new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(), null, 5000L);
        MarzBeanPostProcessor bpp = new MarzBeanPostProcessor(
                registry, new TypeCoercer(), resolver, new SelfRegistrar());

        FlagHolder bean = new FlagHolder();
        bpp.postProcessAfterInitialization(bean, "flagHolder");
        assertThat(registry.getBindings("x.flag")).hasSize(1);

        bpp.postProcessBeforeDestruction(bean, "flagHolder");

        assertThat(registry.getBindings("x.flag")).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scope enforcement — Spring context
    // ═══════════════════════════════════════════════════════════════════

    @Configuration
    static class PrototypeConfig {
        @Bean
        @Scope("prototype")
        FlagHolder prototypeFlag() {
            return new FlagHolder();
        }

        // A singleton that injects the prototype forces it to be instantiated at startup.
        @Bean
        Object prototypeConsumer(FlagHolder prototypeFlag) {
            return new Object();
        }
    }

    @Configuration
    static class SingletonConfig {
        @Bean
        FlagHolder singletonFlag() {
            return new FlagHolder();
        }
    }

    @Test
    @DisplayName("a prototype-scoped @Marz bean fails the context with an actionable message")
    void prototypeScope_failsFast() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarzAutoConfiguration.class))
                .withUserConfiguration(PrototypeConfig.class)
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("singleton-scoped"));
    }

    @Test
    @DisplayName("a singleton @Marz bean starts cleanly")
    void singletonScope_startsClean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarzAutoConfiguration.class))
                .withUserConfiguration(SingletonConfig.class)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("end-to-end: a singleton's binding is unregistered when the context closes")
    void singletonDestroy_unregisters() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(MarzAutoConfiguration.class, SingletonConfig.class);
        ctx.refresh();

        MarzRegistry registry = ctx.getBean(MarzRegistry.class);
        assertThat(registry.getBindings("x.flag")).hasSize(1);

        ctx.close();

        // Nothing in production calls registry.clear() on shutdown, so an empty
        // result proves the destruction hook unregistered the binding (no leak).
        assertThat(registry.getBindings("x.flag")).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scope detection via the bean factory — request/session + inner-bean branch
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("scope detection via the bean factory")
    class ScopeEdgeCases {

        private MarzRegistry registry;
        private DefaultListableBeanFactory factory;
        private MarzBeanPostProcessor bpp;

        @BeforeEach
        void setUp() {
            registry = new MarzRegistry(mock(ApplicationEventPublisher.class), new TypeCoercer());
            factory = new DefaultListableBeanFactory();
            ConfigSourceResolver resolver = new ConfigSourceResolver(
                    new ConfigFormatParser(), registry, new SourceStrategyResolver(), null, 5000L);
            bpp = new MarzBeanPostProcessor(registry, new TypeCoercer(), resolver, new SelfRegistrar());
            bpp.setBeanFactory(factory); // DefaultListableBeanFactory is a ConfigurableListableBeanFactory
        }

        @Test
        @DisplayName("request-scoped @Marz bean is rejected, with its scope named in the message")
        void requestScope_rejected() {
            RootBeanDefinition bd = new RootBeanDefinition(FlagHolder.class);
            bd.setScope("request");
            factory.registerBeanDefinition("requestBean", bd);

            assertThatThrownBy(() -> bpp.postProcessAfterInitialization(new FlagHolder(), "requestBean"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("singleton-scoped")
                    .hasMessageContaining("request");
            assertThat(registry.getRegisteredKeyCount()).isZero();
        }

        @Test
        @DisplayName("session-scoped @Marz bean is rejected, with its scope named in the message")
        void sessionScope_rejected() {
            RootBeanDefinition bd = new RootBeanDefinition(FlagHolder.class);
            bd.setScope("session");
            factory.registerBeanDefinition("sessionBean", bd);

            assertThatThrownBy(() -> bpp.postProcessAfterInitialization(new FlagHolder(), "sessionBean"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("singleton-scoped")
                    .hasMessageContaining("session");
        }

        @Test
        @DisplayName("inner/anonymous bean (no definition for the name) can't be scope-checked → allowed, not rejected")
        void innerBeanName_absent_allowed() {
            // No bean definition for this name → containsBeanDefinition(beanName) is false,
            // so the scope is indeterminable and the BPP must allow registration (line ~175).
            assertThat(factory.containsBeanDefinition("innerBean")).isFalse();

            bpp.postProcessAfterInitialization(new FlagHolder(), "innerBean"); // must not throw

            assertThat(registry.getBindings("x.flag")).hasSize(1);
        }
    }
}
