package io.lumnik.extraction.spi.ask;

import java.util.List;
import java.util.Map;

/** Result of an analytic ask: the answer, the SQL that ran, the rows, and their count. */
/** {@code dataAsOf}: ISO-8601 end of the scope's last completed ingestion run — how fresh
 *  the answer's ground is. Null on refusals, and when the scope has no completed run. */
public record AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount,
                        String dataAsOf) {
    public AskResult(String answer, String sql, List<Map<String,Object>> rows, int rowCount) {
        this(answer, sql, rows, rowCount, null);
    }
}
