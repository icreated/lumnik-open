package io.lumnik.hub.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The WCAG 2.1 relative-luminance formula itself, not merely its wiring. A test that only asserted
 * "this one passes, that one fails" would stay green over a formula that got the sRGB linearisation
 * wrong — every value would shift together and the two branches would still sort the same way. The
 * three ratios below are computed values, held to two decimals.
 */
class WcagContrastTest {

    @Test
    void pinsTheRatioOnTheColoursAlreadyInPlay() {
        assertThat(WcagContrast.ratio("#8b7ff0", WcagContrast.BUBBLE_TEXT))
            .as("today's --accent, the value V48 seeds — it must not fail its own new rule")
            .isCloseTo(5.499, within(0.01));
        assertThat(WcagContrast.ratio("#4fd1a5", WcagContrast.BUBBLE_TEXT))
            .as("the spec's manifest example")
            .isCloseTo(9.460, within(0.01));
        assertThat(WcagContrast.ratio("#101010", WcagContrast.BUBBLE_TEXT))
            .as("near-black on near-black: #17122e text is functionally invisible on it")
            .isCloseTo(1.053, within(0.01));
    }

    @Test
    void theSeededAccentClearsTheBarItIntroduces() {
        assertThat(WcagContrast.ratio("#8b7ff0", WcagContrast.BUBBLE_TEXT))
            .isGreaterThanOrEqualTo(WcagContrast.AA_NORMAL_TEXT);
        assertThat(WcagContrast.ratio("#101010", WcagContrast.BUBBLE_TEXT))
            .isLessThan(WcagContrast.AA_NORMAL_TEXT);
    }

    @Test
    void isSymmetricAndIndifferentToCaseAndHash() {
        assertThat(WcagContrast.ratio("#8b7ff0", "#17122e"))
            .isCloseTo(WcagContrast.ratio("#17122e", "#8b7ff0"), within(1e-9));
        assertThat(WcagContrast.ratio("#8B7FF0", "17122e"))
            .isCloseTo(5.499, within(0.01));
    }
}
