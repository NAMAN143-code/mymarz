package com.hotswap.annotation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class HotSwapAnnotationTest {

    @HotSwap(key = "test.feature.enabled")
    @SuppressWarnings("unused")
    private boolean minimalField = false;

    @HotSwap(
            key = "test.rate.limit",
            source = "file:///etc/config.yml",
            pollInterval = 10000,
            defaultValue = "100",
            type = HotSwapType.INTEGER,
            description = "Rate limit",
            requiresApproval = true
    )
    @SuppressWarnings("unused")
    private int fullField = 0;

    @HotSwap(key = "api.secret.key", sensitive = true)
    @SuppressWarnings("unused")
    private String sensitiveField = "";

    @Test
    void minimalAnnotation_hasDefaults() throws Exception {
        Field field = getClass().getDeclaredField("minimalField");
        HotSwap annotation = field.getAnnotation(HotSwap.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.key()).isEqualTo("test.feature.enabled");
        assertThat(annotation.source()).isEqualTo("platform://hotswap");
        assertThat(annotation.pollInterval()).isEqualTo(5000L);
        assertThat(annotation.defaultValue()).isEmpty();
        assertThat(annotation.type()).isEqualTo(HotSwapType.INFERRED);
        assertThat(annotation.description()).isEmpty();
        assertThat(annotation.requiresApproval()).isFalse();
        assertThat(annotation.sensitive()).isFalse();
    }

    @Test
    void fullAnnotation_hasExplicitValues() throws Exception {
        Field field = getClass().getDeclaredField("fullField");
        HotSwap annotation = field.getAnnotation(HotSwap.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.key()).isEqualTo("test.rate.limit");
        assertThat(annotation.source()).isEqualTo("file:///etc/config.yml");
        assertThat(annotation.pollInterval()).isEqualTo(10000L);
        assertThat(annotation.defaultValue()).isEqualTo("100");
        assertThat(annotation.type()).isEqualTo(HotSwapType.INTEGER);
        assertThat(annotation.description()).isEqualTo("Rate limit");
        assertThat(annotation.requiresApproval()).isTrue();
    }

    @Test
    void annotation_isRuntimeRetention() {
        assertThat(HotSwap.class.getAnnotation(java.lang.annotation.Retention.class).value())
                .isEqualTo(java.lang.annotation.RetentionPolicy.RUNTIME);
    }

    @Test
    void annotation_targetsFields() {
        assertThat(HotSwap.class.getAnnotation(java.lang.annotation.Target.class).value())
                .containsExactly(java.lang.annotation.ElementType.FIELD);
    }

    @Test
    void sensitiveAnnotation_masksValue() throws Exception {
        Field field = getClass().getDeclaredField("sensitiveField");
        HotSwap annotation = field.getAnnotation(HotSwap.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.key()).isEqualTo("api.secret.key");
        assertThat(annotation.sensitive()).isTrue();
    }
}
