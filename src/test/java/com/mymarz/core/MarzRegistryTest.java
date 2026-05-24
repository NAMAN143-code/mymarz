package com.mymarz.core;

import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for the reverse-index targeted swap model.
 * Key invariant: when a key changes, ONLY the AtomicReferences bound to
 * that key are swapped. All other bindings remain untouched.
 */
class MarzRegistryTest {

    private ApplicationEventPublisher eventPublisher;
    private TypeCoercer typeCoercer;
    private MarzRegistry registry;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        typeCoercer = new TypeCoercer();
        registry = new MarzRegistry(eventPublisher, typeCoercer);
    }

    // ═══════════════════════════════════════════════════════════════════
    // REGISTRATION
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void register_addsBindingToReverseIndex() {
        FieldBinding binding = createBinding("feature.checkout", boolean.class, false);
        registry.register("feature.checkout", binding);

        assertThat(registry.getRegisteredKeyCount()).isEqualTo(1);
        assertThat(registry.getTotalBindingCount()).isEqualTo(1);
        assertThat(registry.getBindings("feature.checkout")).containsExactly(binding);
    }

    @Test
    void register_multipleBindingsForSameKey() {
        // Same config key used by multiple beans (PaymentService + CheckoutService)
        FieldBinding b1 = createBinding("feature.checkout", boolean.class, false, "PaymentService", "checkoutEnabled");
        FieldBinding b2 = createBinding("feature.checkout", boolean.class, false, "CheckoutService", "enabled");

        registry.register("feature.checkout", b1);
        registry.register("feature.checkout", b2);

        assertThat(registry.getRegisteredKeyCount()).isEqualTo(1);
        assertThat(registry.getTotalBindingCount()).isEqualTo(2);
        assertThat(registry.getBindings("feature.checkout")).containsExactly(b1, b2);
    }

    // ═══════════════════════════════════════════════════════════════════
    // TARGETED SWAP — the core invariant
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void onSourceChange_swapsOnlyChangedKey() {
        // Register 3 keys — checkout, darkMode, fraudCheck
        AtomicReference<Object> checkoutRef = new AtomicReference<>(false);
        AtomicReference<Object> darkModeRef = new AtomicReference<>(false);
        AtomicReference<Object> fraudCheckRef = new AtomicReference<>(true);

        registry.register("feature.checkout", binding("feature.checkout", boolean.class, checkoutRef));
        registry.register("feature.darkMode", binding("feature.darkMode", boolean.class, darkModeRef));
        registry.register("feature.fraudCheck", binding("feature.fraudCheck", boolean.class, fraudCheckRef));

        // Change ONLY checkout
        registry.onSourceChange("file:///config.yml", Map.of("feature.checkout", "true"));

        // ONLY checkout was swapped
        assertThat(checkoutRef.get()).isEqualTo(true);
        // darkMode and fraudCheck are UNTOUCHED
        assertThat(darkModeRef.get()).isEqualTo(false);
        assertThat(fraudCheckRef.get()).isEqualTo(true);
    }

    @Test
    void onSourceChange_swapsAllBindingsForChangedKey() {
        // Same key bound to 3 different beans
        AtomicReference<Object> ref1 = new AtomicReference<>(false);
        AtomicReference<Object> ref2 = new AtomicReference<>(false);
        AtomicReference<Object> ref3 = new AtomicReference<>(false);

        registry.register("feature.checkout", binding("feature.checkout", boolean.class, ref1, "PaymentService"));
        registry.register("feature.checkout", binding("feature.checkout", boolean.class, ref2, "CheckoutService"));
        registry.register("feature.checkout", binding("feature.checkout", boolean.class, ref3, "AnalyticsService"));

        registry.onSourceChange("file:///config.yml", Map.of("feature.checkout", "true"));

        // ALL three refs swapped
        assertThat(ref1.get()).isEqualTo(true);
        assertThat(ref2.get()).isEqualTo(true);
        assertThat(ref3.get()).isEqualTo(true);
    }

    @Test
    void onSourceChange_ignoresUnregisteredKeys() {
        AtomicReference<Object> ref = new AtomicReference<>(false);
        registry.register("feature.checkout", binding("feature.checkout", boolean.class, ref));

        // Change a key that has no binding
        registry.onSourceChange("file:///config.yml", Map.of("feature.nonexistent", "true"));

        // Original binding untouched
        assertThat(ref.get()).isEqualTo(false);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onSourceChange_firesEventOnlyWhenValueActuallyChanges() {
        AtomicReference<Object> ref = new AtomicReference<>(true);
        registry.register("feature.checkout", binding("feature.checkout", boolean.class, ref));

        // "Change" to the same value — should NOT fire event
        registry.onSourceChange("file:///config.yml", Map.of("feature.checkout", "true"));
        verify(eventPublisher, never()).publishEvent(any());

        // Change to a different value — SHOULD fire event
        registry.onSourceChange("file:///config.yml", Map.of("feature.checkout", "false"));
        verify(eventPublisher, times(1)).publishEvent(any(MarzEvent.class));
    }

    @Test
    void onSourceChange_eventContainsCorrectValues() {
        AtomicReference<Object> ref = new AtomicReference<>(100);
        registry.register("rate.limit", binding("rate.limit", int.class, ref));

        registry.onSourceChange("file:///config.yml", Map.of("rate.limit", "200"));

        ArgumentCaptor<MarzEvent> captor = ArgumentCaptor.forClass(MarzEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        MarzEvent event = captor.getValue();
        assertThat(event.getKey()).isEqualTo("rate.limit");
        assertThat(event.getOldValue()).isEqualTo(100);
        assertThat(event.getNewValue()).isEqualTo(200);
        assertThat(event.getConfigSource()).isEqualTo("file:///config.yml");
    }

    @Test
    void onSourceChange_handlesMultipleChangedKeysInOneBatch() {
        AtomicReference<Object> checkoutRef = new AtomicReference<>(false);
        AtomicReference<Object> limitRef = new AtomicReference<>(100);
        AtomicReference<Object> nameRef = new AtomicReference<>("old");

        registry.register("feature.checkout", binding("feature.checkout", boolean.class, checkoutRef));
        registry.register("rate.limit", binding("rate.limit", int.class, limitRef));
        registry.register("app.name", binding("app.name", String.class, nameRef));

        // Batch change: 2 keys changed, 1 unchanged
        registry.onSourceChange("file:///config.yml", Map.of(
                "feature.checkout", "true",
                "rate.limit", "200"
        ));

        assertThat(checkoutRef.get()).isEqualTo(true);
        assertThat(limitRef.get()).isEqualTo(200);
        assertThat(nameRef.get()).isEqualTo("old"); // UNTOUCHED
    }

    @Test
    void onSourceChange_typeCoercion_int() {
        AtomicReference<Object> ref = new AtomicReference<>(0);
        registry.register("rate.limit", binding("rate.limit", int.class, ref));

        registry.onSourceChange("test", Map.of("rate.limit", "500"));

        assertThat(ref.get()).isEqualTo(500);
    }

    @Test
    void onSourceChange_typeCoercion_double() {
        AtomicReference<Object> ref = new AtomicReference<>(0.0);
        registry.register("rate.multiplier", binding("rate.multiplier", double.class, ref));

        registry.onSourceChange("test", Map.of("rate.multiplier", "3.14"));

        assertThat(ref.get()).isEqualTo(3.14);
    }

    @Test
    void onSourceChange_typeCoercion_string() {
        AtomicReference<Object> ref = new AtomicReference<>("");
        registry.register("app.greeting", binding("app.greeting", String.class, ref));

        registry.onSourceChange("test", Map.of("app.greeting", "Hello World"));

        assertThat(ref.get()).isEqualTo("Hello World");
    }

    // ═══════════════════════════════════════════════════════════════════
    // HEALTH / METRICS
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void getStateSnapshot_returnsCurrentValues() {
        AtomicReference<Object> ref = new AtomicReference<>(true);
        registry.register("feature.checkout", binding("feature.checkout", boolean.class, ref));

        Map<String, MarzRegistry.FieldStateSnapshot> snapshot = registry.getStateSnapshot();

        assertThat(snapshot).containsKey("feature.checkout");
        assertThat(snapshot.get("feature.checkout").value()).isEqualTo(true);
        assertThat(snapshot.get("feature.checkout").bindingCount()).isEqualTo(1);
        assertThat(snapshot.get("feature.checkout").type()).isEqualTo("boolean");
    }

    @Test
    void clear_removesEverything() {
        registry.register("a", createBinding("a", boolean.class, false));
        registry.clear();

        assertThat(registry.getRegisteredKeyCount()).isZero();
        assertThat(registry.getTotalBindingCount()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════════
    // FIELD WRITEBACK — KAN-24 fix verification
    // ═══════════════════════════════════════════════════════════════════

    @Test
    void onSourceChange_writesBackToActualBeanField() throws Exception {
        // Create a real bean with a real field — this is the end-to-end proof
        TestSwapBean bean = new TestSwapBean();
        bean.enabled = false;

        Field field = TestSwapBean.class.getDeclaredField("enabled");
        field.setAccessible(true);

        AtomicReference<Object> ref = new AtomicReference<>(false);
        FieldBinding binding = new FieldBinding(
                bean, "TestSwapBean", "enabled",
                field, ref, boolean.class,
                "feature.enabled", "file:///config.yml", false
        );
        registry.register("feature.enabled", binding);

        // Swap via onSourceChange
        registry.onSourceChange("file:///config.yml", Map.of("feature.enabled", "true"));

        // AtomicReference is updated (existing behavior)
        assertThat(ref.get()).isEqualTo(true);
        // AND the actual field on the bean is updated (KAN-24 fix)
        assertThat(bean.enabled).isTrue();
    }

    @Test
    void onSourceChange_writesBackIntField() throws Exception {
        TestSwapBean bean = new TestSwapBean();
        bean.maxRequests = 100;

        Field field = TestSwapBean.class.getDeclaredField("maxRequests");
        field.setAccessible(true);

        AtomicReference<Object> ref = new AtomicReference<>(100);
        FieldBinding binding = new FieldBinding(
                bean, "TestSwapBean", "maxRequests",
                field, ref, int.class,
                "rate.limit", "file:///config.yml", false
        );
        registry.register("rate.limit", binding);

        registry.onSourceChange("file:///config.yml", Map.of("rate.limit", "500"));

        assertThat(ref.get()).isEqualTo(500);
        assertThat(bean.maxRequests).isEqualTo(500);
    }

    @Test
    void onSourceChange_writesBackToMultipleBeanFields() throws Exception {
        TestSwapBean bean1 = new TestSwapBean();
        bean1.enabled = false;
        TestSwapBean bean2 = new TestSwapBean();
        bean2.enabled = false;

        Field field1 = TestSwapBean.class.getDeclaredField("enabled");
        field1.setAccessible(true);
        Field field2 = TestSwapBean.class.getDeclaredField("enabled");
        field2.setAccessible(true);

        AtomicReference<Object> ref1 = new AtomicReference<>(false);
        AtomicReference<Object> ref2 = new AtomicReference<>(false);

        registry.register("feature.enabled", new FieldBinding(
                bean1, "Bean1", "enabled", field1, ref1, boolean.class,
                "feature.enabled", "file:///config.yml", false));
        registry.register("feature.enabled", new FieldBinding(
                bean2, "Bean2", "enabled", field2, ref2, boolean.class,
                "feature.enabled", "file:///config.yml", false));

        registry.onSourceChange("file:///config.yml", Map.of("feature.enabled", "true"));

        assertThat(bean1.enabled).isTrue();
        assertThat(bean2.enabled).isTrue();
    }

    /** Test bean with volatile fields for writeback verification. */
    static class TestSwapBean {
        volatile boolean enabled;
        volatile int maxRequests;
        volatile String greeting;
    }

    /** Bean with non-volatile field — should be rejected by BeanPostProcessor. */
    static class NonVolatileBean {
        boolean enabled;  // NOT volatile — BeanPostProcessor should reject this
    }

    /** Bean with static field — should be rejected. */
    static class StaticFieldBean {
        static volatile boolean enabled;
    }

    /** Bean with final field — should be rejected. */
    static class FinalFieldBean {
        final boolean enabled = false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private FieldBinding createBinding(String key, Class<?> type, Object initialValue) {
        return new FieldBinding(
                new Object(), "TestBean", "testField",
                null, new AtomicReference<>(initialValue), type,
                key, "file:///config.yml", false
        );
    }

    private FieldBinding createBinding(String key, Class<?> type, Object initialValue,
                                        String beanClass, String fieldName) {
        return new FieldBinding(
                new Object(), beanClass, fieldName,
                null, new AtomicReference<>(initialValue), type,
                key, "file:///config.yml", false
        );
    }

    private FieldBinding binding(String key, Class<?> type, AtomicReference<Object> ref) {
        return new FieldBinding(new Object(), "TestBean", "testField", null, ref, type, key, "file:///config.yml", false);
    }

    private FieldBinding binding(String key, Class<?> type, AtomicReference<Object> ref, String beanClass) {
        return new FieldBinding(new Object(), beanClass, "testField", null, ref, type, key, "file:///config.yml", false);
    }
}
