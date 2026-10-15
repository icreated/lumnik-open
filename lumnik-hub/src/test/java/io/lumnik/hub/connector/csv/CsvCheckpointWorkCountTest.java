package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lot-5b O(R) proof — deterministic parse-work counting, no clocks.
 *
 * <p>The claim: with the checkpoint-anchor fast path, a chunked run over a CSV file parses the
 * file ONCE — every record is tokenized at most twice across the whole run (once when delivered,
 * plus at most one re-read of the record peeked at a window boundary). The legacy skip-parse path
 * re-tokenizes the whole already-consumed prefix on every window, i.e. O(R²/K) records tokenized
 * for R rows in windows of K — measurably, not by assertion of intent.
 *
 * <p>Fixture: N rows in ≥ 10 windows. Skip-parse work: window k tokenizes k·K records + 1 peek
 * → ~N·(W+1)/2 total (5 509 for N=1000, K=100). Fast-path work: ≤ 2N.
 */
class CsvCheckpointWorkCountTest {

  private static final CsvFileConnectorType.ParserOverrides DEFAULTS =
      new CsvFileConnectorType.ParserOverrides(',', StandardCharsets.UTF_8,
          true, true, List.of("", "NULL", "null", "#N/A"), null);

  @Test
  void every_record_is_tokenized_at_most_twice_across_the_whole_run(@TempDir Path tmp) throws Exception {
    int n = 1000;
    int chunkSize = 100;   // → 10 windows
    Path file = tmp.resolve("big.csv");
    StringBuilder sb = new StringBuilder("id,name\n");
    for (int i = 1; i <= n; i++) {
      sb.append(i).append(",row-").append(i).append('\n');
    }
    Files.writeString(file, sb.toString());

    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);

    List<Map<String, Object>> all = new ArrayList<>();
    ConnectorCursor cursor = ConnectorCursor.empty();
    int reads = 0;
    while (true) {
      ReadResult<Map<String, Object>> r = reader.read(chunkSize, cursor, ReaderTestContexts.newRun());
      reads++;
      if (r instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        all.addAll(hm.records());
        cursor = hm.nextCursor();
      } else {
        all.addAll(((ReadResult.EndOfStream<Map<String, Object>>) r).records());
        break;
      }
    }

    // Conservation first: the speedup must not cost a single row, nor reorder any.
    assertThat(all).hasSize(n);
    assertThat(all.get(0).get("id")).isEqualTo("1");
    assertThat(all.get(n - 1).get("id")).isEqualTo(String.valueOf(n));
    assertThat(reads).isGreaterThanOrEqualTo(10);

    // The O(R) claim. Skip-parse re-tokenizes the consumed prefix per window (~5 509 here);
    // the checkpoint fast path parses the file once (~1 009: N delivered + one peek re-read
    // per window boundary).
    long tokenized = reader.rowsTokenized();
    assertThat(tokenized)
        .as("records tokenized across the run (N=%d, %d reads) — must be O(R), not O(R²/K)",
            n, reads)
        .isLessThanOrEqualTo(2L * n);
  }
}
