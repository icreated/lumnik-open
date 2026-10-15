package io.lumnik.hub.ask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * What the phrasing model reads of a result (#1067): the whole result when its SERIALISED form — the JSON actually
 * put in the prompt, escapes and all — fits a budget of characters, otherwise the longest prefix that does. Sending
 * up to 500 rows blindly cost minutes of inference (a 100-row catalogue: 93 s, the model enumerating every
 * reference), and past a few dozen rows the model's own count was wrong anyway; the exact total is the code's to give,
 * the rows stay the user's to read.
 *
 * <p>A result that fits is read untouched — a lookup of one long description is read in full. Only a result that does
 * not fit has its long cells cut, in the copy the model reads; the result itself is never altered.
 */
final class AnswerRows {

    /** A longer cell is cut, with an ellipsis, in what the model reads of an over-budget result. */
    static final int CELL_CAP = 300;

    /** The rows the model reads and how many the query returned. */
    record Window(List<Map<String, Object>> shown, int total) {
        boolean truncated() {
            return shown.size() < total;
        }
    }

    private AnswerRows() {}

    /** The whole result if it fits {@code budgetChars}, else the longest prefix (cells cut) that does; at least one row, itself squeezed to fit. */
    static Window fit(List<Map<String, Object>> rows, int budgetChars) {
        int n = rows.size();
        if (n == 0 || serialisedSize(rows) <= budgetChars) return new Window(rows, n);
        List<Map<String, Object>> cut = rows.stream().map(r -> cutLongCells(r, CELL_CAP)).toList();
        int shown = largest(1, n - 1, m -> serialisedSize(cut.subList(0, m)) <= budgetChars); // the one-row floor otherwise
        if (shown == 1 && serialisedSize(cut.subList(0, 1)) > budgetChars) {
            return new Window(List.of(squeeze(cut.get(0), budgetChars)), n);
        }
        return new Window(cut.subList(0, shown), n);
    }

    /** The largest k in [lo, hi] for which {@code fits} holds (it is monotone: true up to a point, then false); {@code lo} if none. */
    private static int largest(int lo, int hi, IntPredicate fits) {
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (fits.test(mid)) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /**
     * The one row that is read although it alone is over budget (many columns of long text): its cells are cut shorter
     * until it fits, and past one character per cell its last columns are left out (silently; it takes hundreds of columns at the
     * default budget) — the budget holds for any row.
     */
    private static Map<String, Object> squeeze(Map<String, Object> row, int budgetChars) {
        int cap = largest(1, CELL_CAP, c -> serialisedSize(List.of(cutLongCells(row, c))) <= budgetChars);
        Map<String, Object> narrow = cutLongCells(row, cap);
        return firstColumns(narrow, largest(1, narrow.size(), k -> serialisedSize(List.of(firstColumns(narrow, k))) <= budgetChars));
    }

    private static Map<String, Object> firstColumns(Map<String, Object> row, int count) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (out.size() == count) break;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The JSON the model reads for these rows — the prompt's own serialisation. */
    static String serialise(List<Map<String, Object>> rows) {
        return AskToSqlAgent.fenced(AskToSqlAgent.promptRows(rows));
    }

    static int serialisedSize(List<Map<String, Object>> rows) {
        return serialise(rows).length();
    }

    /** A copy of the row whose text cells are at most {@code cap} characters (code points) plus an ellipsis. */
    private static Map<String, Object> cutLongCells(Map<String, Object> row, int cap) {
        Map<String, Object> out = new LinkedHashMap<>(row.size() * 2);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof Number || v instanceof Boolean) {
                out.put(e.getKey(), v);
                continue;
            }
            String text = v.toString();
            out.put(e.getKey(), text.codePointCount(0, text.length()) <= cap ? v
                : text.substring(0, text.offsetByCodePoints(0, cap)) + "…");
        }
        return out;
    }
}
