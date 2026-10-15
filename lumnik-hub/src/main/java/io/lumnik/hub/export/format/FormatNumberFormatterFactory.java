package io.lumnik.hub.export.format;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * {@code format-number}: a canonical decimal in plain digits — no grouping, no exponent — with the
 * partner's {@code decimal-separator} and, when given, {@code scale} digits after it, rounded
 * half-up (commercial rounding: {@code 0.125} → {@code 0.13}).
 */
@ApplicationScoped
public class FormatNumberFormatterFactory implements ColumnFormatterFactory {

    private static final Set<String> PARAMS = Set.of("decimal-separator", "scale");
    private static final int NO_SCALE = -1;
    /** Digits allowed on each side of the separator. The exponent comes from the source data, so
     *  without a bound one value ({@code 1E+999999999}) would make toPlainString() build a
     *  gigabyte string — and setScale() pay for the same digits. Checked before either runs. */
    static final int MAX_DIGITS = 1000;

    @Override
    public String kind() {
        return "format-number";
    }

    @Override
    public ColumnFormatter compile(JsonNode params) {
        FormatterParams.refuseUnknown(params, PARAMS);
        String separator = FormatterParams.text(params, "decimal-separator");
        if (separator == null) separator = ".";
        if (!separator.equals(".") && !separator.equals(","))
            throw new IllegalArgumentException("decimal-separator must be \".\" or \",\"");
        int scale = FormatterParams.integer(params, "scale", NO_SCALE, 0, 18, "an integer from 0 to 18");
        char sep = separator.charAt(0);
        return value -> {
            BigDecimal number;
            try {
                number = new BigDecimal(value.strip());
            } catch (NumberFormatException e) {
                throw new ColumnFormatException("not a decimal number");
            }
            if ((long) number.precision() - number.scale() > MAX_DIGITS || number.scale() > MAX_DIGITS)
                throw new ColumnFormatException("too large to write as plain digits (at most "
                    + MAX_DIGITS + " digits on each side of the separator)");
            if (scale != NO_SCALE) number = number.setScale(scale, RoundingMode.HALF_UP);
            String plain = number.toPlainString();
            return sep == '.' ? plain : plain.replace('.', sep);
        };
    }
}
