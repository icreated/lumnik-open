package io.lumnik.hub.process;

import io.lumnik.hub.view.ViewFilter;

import java.math.BigDecimal;
import java.time.Instant;
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
 * A native floating-point value whose text form is scientific notation is treated as
 * incomparable in both worlds (the shared NUMERIC_SHAPE), keeping the two worlds consistent.
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
                ok = String.valueOf(v).equals(f.value());
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

    private static Integer compareNumeric(Object v, String operand) {
        BigDecimal left;
        try {
            if (v instanceof Number n) {
                if ((v instanceof Double d && !Double.isFinite(d))
                        || (v instanceof Float f && !Float.isFinite(f))) return null;  // NaN/±Infinity: incomparable
                String s = n.toString();
                if (!s.matches(ViewFilter.NUMERIC_SHAPE)) return null;   // scientific-magnitude → incomparable, same as the SQL guard
                left = new BigDecimal(s);
            } else {
                String s = String.valueOf(v);
                if (!s.matches(ViewFilter.NUMERIC_SHAPE)) return null;   // same shape as the SQL guard
                left = new BigDecimal(s.strip());
            }
        } catch (NumberFormatException e) {
            return null;   // an unparseable number is incomparable, never a thrown error
        }
        return left.compareTo(new BigDecimal(operand.strip()));
    }

    private static Integer compareTemporal(Object v, String operand) {
        Instant left;
        if (v instanceof Date d) {           // covers java.sql.Timestamp/Date
            left = Instant.ofEpochMilli(d.getTime());
        } else if (v instanceof Instant i) {
            left = i;
        } else {
            left = ViewFilter.parseTemporal(String.valueOf(v));
            if (left == null) return null;
        }
        return left.compareTo(ViewFilter.parseTemporal(operand));
    }
}
