package com.mymarz.annotation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class MarzAnnotationTest {

    @Marz(key = "test.feature.enabled")
    @SuppressWarnings("unused")
    private boolean minimalField = false;

    @Marz(
            key = "test.rate.limit",
            source = "file:///etc/config.yml",
            pollInterval = 10000,
            defaultValue = "100",
            type = MarzType.INTEGER,
            description = "Rate limit",
            requiresApproval = true
    )
    @SuppressWarnings("unused")
    private int fullField = 0;

    @Marz(key = "api.secret.key", sensitive = true)
    @SuppressWarnings("unused")
    private String sensitiveField = "";

    @Test
    void minimalAnnotation_hasDefaults() throws Exception {
        Field field = getClass().getDeclaredField("minimalField");
        MARZ annotation = field.getAnnotation(MARZ.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.key()).isEqualTo("test.feature.enabled");
        assertThat(annotation.source()).isEqualTo("platform://marz");
        assertThat(annotation.pollInterval()).isEqualTo(5000L);
        assertThat(annotation.defaultValue()).isEmpty();
        assertThat(annotation.type()).isEqualTo(MarzType.INFERRED);
        assertThat(annotation.description()).isEmpty();
        assertThat(annotation.requiresApproval()).isFalse();
        assertThat(annotation.sensitive()).isFalse();
    }

    @Test
    void fullAnnotation_hasExplicitValues() throws Exception {
        Field field = getClass().getDeclaredField("fullField");
        MARZ annotation = field.getAnnotation(MARZ.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.key()).isEqualTo("test.rate.limit");
        assertThat(annotation.source()).isEqualTo("file:///etc/config.yml");
        assertThat(annotation.pollInterval()).isEqualTo(10000L);
        assertThat(annotation.defaultValue()).isEqualTo("100");
        assertThat(annotation.type()).isEqualTo(MarzType.INTEGER);
        assertThat(annotation.description()).isEqualTo("Rate limit");
        assertThat(annotation.requiresApproval()).isTrue();
    }

    @Test
    void annotation_isRuntimeRetention() {
        assertThat(MARZ.class.getAnnotation(java.lang.annotation.Retention.class).value())
                .isEqualTo(java.lang.annotation.RetentionPolicy.RUNTIME);
    }

    @Test
    void annotation_targetsFields() {
        assertThat(MARZ.class.getAnnotation(java.lang.annotation.Target.class).value())
                .containsExactly(java.lang.annotation.ElementType.FIELD);
    }

    @Test
    void sensitiveAnnotation_masksValue() throws Exception {
        Field field = getClass().getDeclaredField("sensitiveField");
        MARZ annotation = field.getAnnotation(MARZ.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.key()).isEqualTo("api.secret.key");
        assertThat(annotation.sensitive()).isTrue();
    }
}
