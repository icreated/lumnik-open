package io.lumnik.extraction.spi.ask;

import java.util.regex.Pattern;

/**
 * Pure validator/normalizer for LLM-generated SQL.
 * Layers 2 (banned-token denylist) and 4 (LIMIT bounding) of a five-layer defense in depth:
 * layer 1 is the read-only datasource the query executes against, layer 3 is schema
 * confinement at prompt time, and layer 5 is echoing the executed SQL back in the response
 * so nothing runs unseen.
 */
public class SqlGuard {

    public static class SqlRejectedException extends RuntimeException {
        public SqlRejectedException(String msg) { super(msg); }
    }

    private static final Pattern BANNED = Pattern.compile(
        "(?i)\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|"
        + "copy|vacuum|reindex|pg_sleep|pg_read|pg_catalog|information_schema|"
        + "lo_import|lo_export|dblink)\\b");

    private static final Pattern LIMIT_TAIL = Pattern.compile("(?i)\\blimit\\s+(\\d+)\\s*$");

    private final int maxRows;

    public SqlGuard(int maxRows) { this.maxRows = maxRows; }

    /** Returns the normalized, LIMIT-bounded SQL, or throws SqlRejectedException. */
    public String check(String raw) {
        if (raw == null || raw.isBlank()) throw new SqlRejectedException("empty SQL");

        String sql = raw.trim();
        sql = sql.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();

        if (sql.contains(";")) throw new SqlRejectedException("multiple statements not allowed");

        String lower = sql.toLowerCase();
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw new SqlRejectedException("only SELECT/WITH allowed");
        }
        if (BANNED.matcher(sql).find()) {
            throw new SqlRejectedException("banned token in SQL");
        }

        var m = LIMIT_TAIL.matcher(sql);
        if (m.find()) {
            int existing = Integer.parseInt(m.group(1));
            if (existing > maxRows) sql = m.replaceAll("LIMIT " + maxRows);
            return sql;
        }
        return sql + " LIMIT " + maxRows;
    }
}
