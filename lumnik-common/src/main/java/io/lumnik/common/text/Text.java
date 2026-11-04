package io.lumnik.common.text;

/**
 * The "null or blank" test for a {@code String}, named once.
 *
 * <p>Blank means {@link String#isBlank()}: null, empty, or white space only. A site that means
 * <em>exactly</em> empty ({@code isEmpty}) says something different for {@code "  "} and keeps
 * its own test. For Jackson nodes see the hub's {@code JsonNodes}, which also covers the
 * {@code NullNode.asText() == "null"} trap.
 */
public final class Text {
    private Text() {}

    /** True iff the string is null, empty, or white space only. */
    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** True iff the string has at least one non-white-space character — the negation of {@link #blank}. */
    public static boolean present(String s) {
        return !blank(s);
    }
}
