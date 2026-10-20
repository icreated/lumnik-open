package io.lumnik.hub.process;

import io.lumnik.hub.view.ViewFilter;
import io.lumnik.hub.sql.TemporalText;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory evaluation of resolved when-conditions against one row (AND semantics).
 * A null/absent value never matches — honest: an unknown cannot satisfy a condition. The
 * ordering operators extend the same rule: an INCOMPARABLE row value (dirty text under a
 * numeric/temporal comparison — same conservative shapes as the SQL world) never matches.
 * `=` is exact text equality on String.valueOf; `~` is case-insensitive contains.
 * A native Number is compared by value (a Double whose text is 2.0E-5 is a legitimate 0.00002);
 * only a STRING is held to the strict numeric text shape, as a dirty CSV cell must not match.
 * {@code =} on a native Number against a numeric operand is equal by value too.
 */
public final class ProcessRuleEvaluator {

    private ProcessRuleEvaluator() {}

    public static boolean matches(Map<String, Object> row, List<ViewFilter> conditions) {
        for (ViewFilter f : conditions) {
            Object v = row.get(f.column());
            if (v == null) return false;
            boolean ok;
            if ("~".equals(f.op())) {
                ok = String.valueOf(v).toLowerCase(Locale.ROOT)
                        .contains(f.value().toLowerCase(Locale.ROOT));
            } else if ("=".equals(f.op())) {
                ok = equalsValue(v, f);
            } else {
                ok = ordered(v, f);
            }
            if (!ok) return false;
        }
        return true;
    }

    /** cmp(row, operand) applied to the operator; an incomparable row value never matches. */
    private static boolean ordered(Object v, ViewFilter f) {
        if (f.operandType() == null) return false;   // not a valid ordering comparison → no match
        Integer cmp = f.operandType() == ViewFilter.OperandType.NUMERIC
            ? compareNumeric(v, f.value()) : compareTemporal(v, f.value());
        if (cmp == null) return false;
        return switch (f.op()) {
            case ">"  -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<"  -> cmp < 0;
            case "<=" -> cmp <= 0;
            default   -> false;
        };
    }

    /** {@code =}: a native Number against a numeric operand is equal by VALUE (Double 5.0 = 5, BigDecimal
     *  5.00 = 5, 2.0E-5 = 0.00002); a DATE or timestamp column against a date is equal as an INSTANT, as
     *  the view's SQL compares it (#1310); anything else is an exact text comparison, as before. */
    private static boolean equalsValue(Object v, ViewFilter f) {
        return switch (v) {
            case Timestamp t when f.temporalOperand() -> compareTemporal(t, f.value()) == 0;
            case java.sql.Date d when f.temporalOperand() -> compareTemporal(d, f.value()) == 0;
            case Number n when f.numericOperand() != null -> {
                BigDecimal left = nativeNumber(n);
                yield left != null && left.compareTo(f.numericOperand()) == 0;
            }
            default -> String.valueOf(v).equals(f.value());
        };
    }

    /** A native Number by value. It has no dirty form, so it is NOT held to the text shape the SQL guard
     *  applies to stored text: Double.toString() is scientific for small and large magnitudes
     *  (2.0E-5, 1.0E20), and those are legitimate values. NaN and ±Infinity are incomparable; null. */
    private static BigDecimal nativeNumber(Number n) {
        if ((n instanceof Double d && !Double.isFinite(d)) || (n instanceof Float f && !Float.isFinite(f))) return null;
        try {
            return new BigDecimal(n.toString());   // a Float's shortest text, not its binary expansion
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer compareNumeric(Object v, String operand) {
        BigDecimal left;
        if (v instanceof Number n) {
            left = nativeNumber(n);
            if (left == null) return null;
        } else {
            String s = String.valueOf(v);
            if (!s.matches(ViewFilter.NUMERIC_SHAPE)) return null;   // dirty text: the same shape as the SQL guard
            try {
                left = new BigDecimal(s.strip());
            } catch (NumberFormatException e) {
                return null;   // an unparseable number is incomparable, never a thrown error
            }
        }
        return left.compareTo(new BigDecimal(operand.strip()));
    }

    private static Integer compareTemporal(Object v, String operand) {
        Instant left = switch (v) {
            // A DATE column: pgjdbc's midnight in the JVM's zone, read as the day it names at midnight
            // UTC — the rung TemporalText gives a date-only operand, whatever zone the hub runs in (#1301).
            case java.sql.Date d -> d.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            case Timestamp t -> t.toInstant();   // keeps the microseconds getTime() would drop
            case Date d -> Instant.ofEpochMilli(d.getTime());   // not toInstant(): java.sql.Time throws on it
            case Instant i -> i;
            default -> TemporalText.parse(String.valueOf(v));
        };
        if (left == null) return null;
        return left.compareTo(TemporalText.parse(operand));
    }
}
