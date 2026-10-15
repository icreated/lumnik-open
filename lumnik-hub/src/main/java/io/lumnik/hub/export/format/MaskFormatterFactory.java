package io.lumnik.hub.export.format;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * {@code mask}: keeps {@code keep-first} and {@code keep-last} characters and masks the rest,
 * counting code points. Fails closed, unlike the inbound hook: a value not longer than
 * {@code keep-first + keep-last} is masked entirely — on a file leaving the house, letting it
 * through would publish the very value the column was meant to hide.
 */
@ApplicationScoped
public class MaskFormatterFactory implements ColumnFormatterFactory {

    private static final Set<String> PARAMS = Set.of("keep-first", "keep-last", "mask-char");
    private static final String NON_NEGATIVE = "a non-negative integer";

    @Override
    public String kind() {
        return "mask";
    }

    @Override
    public ColumnFormatter compile(JsonNode params) {
        FormatterParams.refuseUnknown(params, PARAMS);
        int keepFirst = FormatterParams.integer(params, "keep-first", 0, 0, Integer.MAX_VALUE, NON_NEGATIVE);
        int keepLast = FormatterParams.integer(params, "keep-last", 0, 0, Integer.MAX_VALUE, NON_NEGATIVE);
        String maskChar = FormatterParams.text(params, "mask-char");
        if (maskChar == null) maskChar = "*";
        if (maskChar.codePointCount(0, maskChar.length()) != 1)
            throw new IllegalArgumentException("mask-char must be exactly one character");
        String mask = maskChar;
        return value -> {
            int[] cps = value.codePoints().toArray();
            if ((long) keepFirst + keepLast >= cps.length) return mask.repeat(cps.length);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < cps.length; i++) {
                if (i < keepFirst || i >= cps.length - keepLast) out.appendCodePoint(cps[i]);
                else out.append(mask);
            }
            return out.toString();
        };
    }
}
