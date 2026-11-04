package io.lumnik.common.text;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The one "null or blank" test, so the ~300 hand-written copies can say what they mean. */
class TextTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\n "})
    void nothingToReadIsBlankAndNotPresent(String s) {
        assertThat(Text.blank(s)).isTrue();
        assertThat(Text.present(s)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", " a ", "null", "0", " x"})
    void anythingWithAVisibleCharacterIsPresentAndNotBlank(String s) {
        assertThat(Text.blank(s)).isFalse();
        assertThat(Text.present(s)).isTrue();
    }

    /** {@code isBlank} is Unicode white space, {@code isEmpty} is length 0 — a site that means the latter must not use this. */
    @Test
    void whitespaceOnlyIsBlankEvenThoughItIsNotEmpty() {
        assertThat(" ".isEmpty()).isFalse();
        assertThat(Text.blank(" ")).isTrue();
    }
}
