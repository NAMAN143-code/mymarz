package com.mymarz.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MarzTypeException}.
 *
 * <p>Verifies both constructors expose the message and cause, and confirms
 * it is an unchecked exception (so callers don't have to declare it).</p>
 */
class MarzTypeExceptionTest {

    @Test
    @DisplayName("message-only constructor exposes the message")
    void messageOnlyConstructor() {
        MarzTypeException ex = new MarzTypeException("bad value");

        assertThat(ex.getMessage()).isEqualTo("bad value");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("message + cause constructor preserves the cause chain")
    void messageAndCauseConstructor() {
        NumberFormatException cause = new NumberFormatException("not-a-number");

        MarzTypeException ex = new MarzTypeException("coercion failed", cause);

        assertThat(ex.getMessage()).isEqualTo("coercion failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("is a RuntimeException — does not require declaration")
    void isRuntimeException() {
        assertThat(new MarzTypeException("x"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("can be thrown and caught as MarzTypeException")
    void throwAndCatch() {
        assertThatThrownBy(() -> { throw new MarzTypeException("explode"); })
                .isInstanceOf(MarzTypeException.class)
                .hasMessage("explode");
    }

    @Test
    @DisplayName("null message is preserved (no NPE in constructor)")
    void nullMessage() {
        MarzTypeException ex = new MarzTypeException(null);
        assertThat(ex.getMessage()).isNull();
    }

    @Test
    @DisplayName("null cause is preserved")
    void nullCauseExplicit() {
        MarzTypeException ex = new MarzTypeException("msg", null);
        assertThat(ex.getMessage()).isEqualTo("msg");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("getStackTrace is populated when thrown")
    void hasStackTrace() {
        MarzTypeException ex = new MarzTypeException("trace");
        assertThat(ex.getStackTrace()).isNotEmpty();
    }
}
