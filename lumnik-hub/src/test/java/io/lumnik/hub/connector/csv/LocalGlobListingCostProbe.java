package io.lumnik.hub.connector.csv;

import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.runtime.OwnedRunScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * #898 (d) probe, not a regression test (run by name): what a local-CSV run pays to find its place in a directory
 * of many small files. The engine builds a fresh {@code CsvGlobCursorReader} per chunk; before the run scope held
 * the listing, each chunk listed, stat'ed and sorted the whole directory again before it read a row.
 *
 * <p>Prints, per directory size D (files of {@value #ROWS_PER_FILE} rows, chunk of {@value #CHUNK} rows): one listing,
 * the run's total with one scope for the run, and what the listing would cost if every chunk repeated it.
 */
class LocalGlobListingCostProbe {

    private static final int ROWS_PER_FILE = 20;
    private static final int CHUNK = 500;
    private static final CsvFileConnectorType.ParserOverrides DEFAULTS =
        new CsvFileConnectorType.ParserOverrides(',', StandardCharsets.UTF_8, true, true, List.of("", "NULL"), null);

    @Test
    void measure(@TempDir Path tmp) throws Exception {
        StringBuilder out = new StringBuilder("\n=== #898 (d) local CSV listing cost per chunk, chunk=" + CHUNK
            + " rows, " + ROWS_PER_FILE + " rows/file ===\n");
        for (int files : new int[] {100, 1_000, 5_000, 20_000}) {
            Path dir = Files.createDirectory(tmp.resolve("d" + files));
            for (int f = 0; f < files; f++) {
                StringBuilder sb = new StringBuilder("id,name\n");
                for (int r = 0; r < ROWS_PER_FILE; r++) sb.append(r).append(",n").append(r).append('\n');
                Files.writeString(dir.resolve(String.format("f%06d.csv", f)), sb.toString());
            }
            String glob = dir.resolve("*.csv").toString();
            for (int i = 0; i < 5; i++) CsvGlobResolver.resolve(glob);   // warm up
            long t0 = System.nanoTime();
            int n = 20;
            for (int i = 0; i < n; i++) CsvGlobResolver.resolve(glob);
            double listMs = (System.nanoTime() - t0) / 1e6 / n;

            // The whole run: one scope, a fresh reader per chunk, as ChunkExecutor does.
            ConnectorCursor cursor = ConnectorCursor.empty();
            int chunks = 0;
            long rows = 0;
            long start = System.nanoTime();
            try (OwnedRunScope scope = new OwnedRunScope("probe")) {
                while (true) {
                    var reader = new CsvFileConnectorType.CsvGlobCursorReader(glob, DEFAULTS);
                    ReadResult<Map<String, Object>> r = reader.read(CHUNK, cursor, ReaderTestContexts.of(scope));
                    chunks++;
                    if (r instanceof ReadResult.HasMore<Map<String, Object>> hm) {
                        rows += hm.records().size();
                        cursor = hm.nextCursor();
                        if (hm.records().isEmpty()) break;
                    } else {
                        rows += ((ReadResult.EndOfStream<Map<String, Object>>) r).records().size();
                        break;
                    }
                }
            }
            double totalMs = (System.nanoTime() - start) / 1e6;
            out.append(String.format("D=%6d files: one listing %8.2f ms | run %9.1f ms over %4d chunks, %7d rows | listing if repeated per chunk %5.1f%% of the run%n",
                files, listMs, totalMs, chunks, rows, 100.0 * listMs * chunks / totalMs));
        }
        System.out.println(out);
    }
}
