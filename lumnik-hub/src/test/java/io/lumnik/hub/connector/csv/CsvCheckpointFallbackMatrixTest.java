package io.lumnik.hub.connector.csv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback matrix (lot-5b): corrupt ONE fast-path precondition at a time and prove the
 * read degrades to the skip-parse path — same rows out, conservation intact, and the fallback
 * demonstrably taken (the cursor written after a fallback window is legacy-shaped, because
 * only a checkpointed window can mint checkpoint fields). The fallback is not an afterthought;
 * it is half the test matrix — the worst case is the present.
 */
class CsvCheckpointFallbackMatrixTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static final CsvFileConnectorType.ParserOverrides DEFAULTS =
      new CsvFileConnectorType.ParserOverrides(',', StandardCharsets.UTF_8,
          true, true, List.of("", "NULL", "null", "#N/A"), null);

  private static Path sixRows(Path tmp) throws Exception {
    Path file = tmp.resolve("data.csv");
    Files.writeString(file, "id,name\n1,a\n2,b\n3,c\n4,d\n5,e\n6,f\n");
    return file;
  }

  /** Drain the reader to EndOfStream, mutating the cursor once after the first read. */
  private static List<Map<String, Object>> runWithCursorMutation(
      CsvFileConnectorType.CsvGlobCursorReader reader, int chunkSize,
      UnaryOperator<ConnectorCursor> mutateAfterFirst) {
    List<Map<String, Object>> all = new ArrayList<>();
    ConnectorCursor cursor = ConnectorCursor.empty();
    boolean first = true;
    while (true) {
      ReadResult<Map<String, Object>> r = reader.read(chunkSize, cursor, null);
      if (r instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        all.addAll(hm.records());
        cursor = first ? mutateAfterFirst.apply(hm.nextCursor()) : hm.nextCursor();
        first = false;
      } else {
        all.addAll(((ReadResult.EndOfStream<Map<String, Object>>) r).records());
        return all;
      }
    }
  }

  private static ConnectorCursor editNode(ConnectorCursor c, Consumer<ObjectNode> edit) {
    ObjectNode n = ((ObjectNode) c.value()).deepCopy();
    edit.accept(n);
    return new ConnectorCursor(n, Instant.now());
  }

  private static List<Object> ids(List<Map<String, Object>> rows) {
    return rows.stream().map(r -> r.get("id")).toList();
  }

  @Test
  void fast_path_cursor_carries_the_checkpoint_payload(@TempDir Path tmp) throws Exception {
    Path file = sixRows(tmp);
    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);
    var r = reader.read(2, ConnectorCursor.empty(), null);
    var node = ((ReadResult.HasMore<?>) r).nextCursor().value();
    assertThat(node.get("type").asText()).isEqualTo("CsvFile");
    assertThat(node.get("file").asText()).isEqualTo(file.toString());
    assertThat(node.get("rowsProcessed").asLong()).as("the legacy pair stays first-class").isEqualTo(2L);
    assertThat(node.hasNonNull("anchorByte")).isTrue();
    assertThat(node.hasNonNull("charDelta")).isTrue();
    assertThat(node.hasNonNull("lineBase")).isTrue();
    assertThat(node.get("headers")).isNotNull();
    assertThat(node.get("version").get("size").asLong()).isEqualTo(Files.size(file));
  }

  @Test
  void legacy_two_field_cursor_takes_the_fallback_and_loses_no_rows(@TempDir Path tmp) throws Exception {
    Path file = sixRows(tmp);
    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);
    // Strip the cursor down to what a pre-lot-5b build would have written.
    List<Map<String, Object>> all = runWithCursorMutation(reader, 2, c -> editNode(c, n ->
        n.retain("type", "file", "rowsProcessed")));
    assertThat(ids(all)).containsExactly("1", "2", "3", "4", "5", "6");

    // Fallback demonstrably taken: a fallback (skip>0) window cannot mint checkpoint fields,
    // so the cursor it writes is legacy-shaped for the rest of the file.
    var r2 = reader.read(2, editNode(mid(reader, file), n -> n.retain("type", "file", "rowsProcessed")), null);
    var c2 = ((ReadResult.HasMore<?>) r2).nextCursor().value();
    assertThat(c2.has("anchorByte")).isFalse();
    assertThat(c2.has("version")).isFalse();
  }

  /** A fresh mid-file checkpoint cursor for {@code file} (first read of a fresh reader). */
  private static ConnectorCursor mid(CsvFileConnectorType.CsvGlobCursorReader reader, Path file) {
    var r = reader.read(2, ConnectorCursor.empty(), null);
    return ((ReadResult.HasMore<?>) r).nextCursor();
  }

  @Test
  void mutated_version_token_falls_back_and_loses_no_rows(@TempDir Path tmp) throws Exception {
    Path file = sixRows(tmp);
    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);
    List<Map<String, Object>> all = runWithCursorMutation(reader, 2, c -> editNode(c, n ->
        ((ObjectNode) n.get("version")).put("size", n.get("version").get("size").asLong() + 1)));
    assertThat(ids(all)).containsExactly("1", "2", "3", "4", "5", "6");
  }

  @Test
  void forged_anchor_beyond_eof_is_rejected_as_incomplete_never_resumed(@TempDir Path tmp) throws Exception {
    Path file = sixRows(tmp);
    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);
    // Version token intact, anchor absurd: hasCheckpoint() must refuse it (anchor < size),
    // otherwise a "resume" onto an empty stream would report the file done — silent data loss.
    List<Map<String, Object>> all = runWithCursorMutation(reader, 2, c -> editNode(c, n ->
        n.put("anchorByte", n.get("version").get("size").asLong() + 999)));
    assertThat(ids(all)).containsExactly("1", "2", "3", "4", "5", "6");
  }

  @Test
  void off_allowlist_charset_never_writes_a_checkpoint_and_still_reads_all(@TempDir Path tmp) throws Exception {
    Path file = sixRows(tmp);   // pure ASCII → identical bytes in GB18030
    var overrides = new CsvFileConnectorType.ParserOverrides(',',
        Charset.forName("GB18030"), true, true,
        List.of("", "NULL", "null", "#N/A"), null);
    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), overrides);
    var r = reader.read(2, ConnectorCursor.empty(), null);
    var node = ((ReadResult.HasMore<?>) r).nextCursor().value();
    assertThat(node.has("anchorByte"))
        .as("unverified charset: the cursor must stay legacy-shaped (allowlist, not blocklist)")
        .isFalse();
    List<Map<String, Object>> all = runWithCursorMutation(
        new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), overrides), 2, c -> c);
    assertThat(ids(all)).containsExactly("1", "2", "3", "4", "5", "6");
  }

  @Test
  void truncated_file_fails_the_version_token_and_falls_back(@TempDir Path tmp) throws Exception {
    Path file = sixRows(tmp);
    var reader = new CsvFileConnectorType.CsvGlobCursorReader(file.toString(), DEFAULTS);
    var r1 = reader.read(2, ConnectorCursor.empty(), null);
    List<Map<String, Object>> all = new ArrayList<>(((ReadResult.HasMore<Map<String, Object>>) r1).records());
    ConnectorCursor c1 = ((ReadResult.HasMore<Map<String, Object>>) r1).nextCursor();

    // The file is truncated between chunks: same prefix, rows 4..6 gone. The stored version
    // token no longer matches → the checkpoint must not be trusted; skip-parse re-reads the
    // prefix and delivers what is still there. (Today's path on a mutated file is undefined
    // too — the token turns that into detected, equal-or-better behaviour.)
    Files.writeString(file, "id,name\n1,a\n2,b\n3,c\n");

    ConnectorCursor cursor = c1;
    while (true) {
      var r = reader.read(2, cursor, null);
      if (r instanceof ReadResult.HasMore<Map<String, Object>> hm) {
        all.addAll(hm.records());
        assertThat(hm.nextCursor().value().has("anchorByte"))
            .as("post-fallback windows write legacy cursors").isFalse();
        cursor = hm.nextCursor();
      } else {
        all.addAll(((ReadResult.EndOfStream<Map<String, Object>>) r).records());
        break;
      }
    }
    // Rows 1,2 from the first chunk + row 3 (events 1,2 skipped by rowsProcessed) — nothing
    // double-read, nothing invented.
    assertThat(ids(all)).containsExactly("1", "2", "3");
  }
}
