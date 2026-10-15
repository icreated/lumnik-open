package io.lumnik.hub.proposal;

/** A JSON string literal, spelled as JavaScript's well-formed {@code JSON.stringify} spells it. */
final class JsonText {

    private JsonText() {}

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append('\\').append('"');
                case '\\' -> b.append('\\').append('\\');
                case '\b' -> b.append('\\').append('b');
                case '\f' -> b.append('\\').append('f');
                case '\n' -> b.append('\\').append('n');
                case '\r' -> b.append('\\').append('r');
                case '\t' -> b.append('\\').append('t');
                default -> {
                    if (c < 0x20) {
                        escape(b, c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        b.append(c).append(s.charAt(++i));            // a valid pair stays as it is
                    } else if (Character.isSurrogate(c)) {
                        escape(b, c);                                  // well-formed JSON.stringify: a lone one is escaped
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private static void escape(StringBuilder b, char c) {
        b.append('\\').append('u').append(String.format("%04x", (int) c));
    }
}
