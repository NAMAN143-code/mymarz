package com.mymarz.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lightweight tests for the {@link MarzType} enum.
 *
 * <p>Verifies values are exhaustively enumerated, valueOf round-trips,
 * and names match documented identifiers.</p>
 */
class MarzTypeEnumTest {

    @Test
    @DisplayName("enum contains all documented values")
    void enumValuesPresent() {
        assertThat(MarzType.values())
                .containsExactlyInAnyOrder(
                        MarzType.INFERRED,
                        MarzType.BOOLEAN,
                        MarzType.STRING,
                        MarzType.INTEGER,
                        MarzType.LONG,
                        MarzType.DOUBLE,
                        MarzType.JSON);
    }

    @Test
    @DisplayName("enum has exactly 7 values")
    void enumSize() {
        assertThat(MarzType.values()).hasSize(7);
    }

    @ParameterizedTest
    @EnumSource(MarzType.class)
    @DisplayName("valueOf round-trips for every value")
    void valueOfRoundTrip(MarzType t) {
        assertThat(MarzType.valueOf(t.name())).isSameAs(t);
    }

    @Test
    @DisplayName("valueOf with unknown name throws IllegalArgumentException")
    void valueOfUnknownThrows() {
        assertThatThrownBy(() -> MarzType.valueOf("BIGDECIMAL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("INFERRED is documented as the default mode")
    void inferredExists() {
        assertThat(MarzType.INFERRED.name()).isEqualTo("INFERRED");
    }

    @Test
    @DisplayName("ordinal positions are stable (INFERRED is first)")
    void ordinalStable() {
        // The first declared constant must remain INFERRED — it's the annotation
        // default and adding a value before it would silently change the default.
        assertThat(MarzType.values()[0]).isEqualTo(MarzType.INFERRED);
    }
}
