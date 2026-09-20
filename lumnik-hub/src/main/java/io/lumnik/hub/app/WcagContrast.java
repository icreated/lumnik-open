package io.lumnik.hub.app;

/**
 * WCAG 2.1 relative luminance and contrast ratio, for the one colour a kind:App manifest can
 * declare that is able to make text illegible.
 *
 * <p>{@code --accent} is not only a text colour in {@code styles.css}: {@code .bubble.me} paints it
 * as the BACKGROUND of the métier's own chat messages, with a fixed {@code #17122e} foreground. A
 * hex-format check alone accepts {@code #101010} there, and the app ships with invisible text.
 */
public final class WcagContrast {

    /** The fixed foreground {@code .bubble.me} paints over a declared accentColor. */
    public static final String BUBBLE_TEXT = "#17122e";

    /** WCAG AA, normal text. */
    public static final double AA_NORMAL_TEXT = 4.5;

    private WcagContrast() {}

    /** The contrast ratio between two 6-digit hex colours, with or without a leading {@code #}. */
    public static double ratio(String hexA, String hexB) {
        double a = relativeLuminance(hexA);
        double b = relativeLuminance(hexB);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    static double relativeLuminance(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        double r = linear(Integer.parseInt(h.substring(0, 2), 16));
        double g = linear(Integer.parseInt(h.substring(2, 4), 16));
        double b = linear(Integer.parseInt(h.substring(4, 6), 16));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** sRGB → linear light. The 0.03928 knee and the 2.4 exponent are the formula, not a tuning. */
    private static double linear(int raw) {
        double c = raw / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
