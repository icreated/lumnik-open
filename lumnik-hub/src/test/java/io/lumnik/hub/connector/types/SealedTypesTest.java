package io.lumnik.hub.connector.types;

import io.lumnik.hub.connector.api.types.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SealedTypesTest {

  @Test
  void run_status_variants_exhaustive_switch() {
    RunStatus s = new RunStatus.Completed(Instant.now(), 100, Duration.ofSeconds(5));
    String summary = switch (s) {
      case RunStatus.Created c    -> "created";
      case RunStatus.Running r    -> "running";
      case RunStatus.Completed c  -> "completed:" + c.records();
      case RunStatus.Partial p    -> "partial";
      case RunStatus.Failed f     -> "failed";
      case RunStatus.Cancelled c  -> "cancelled";
    };
    assertThat(summary).isEqualTo("completed:100");
  }

  /**
   * An EXHAUSTIVE switch, not two instanceof checks: the test was named for the variants and
   * covered half of them, so a new member could land without a single test noticing. The engine's
   * own switch is the real guardian — this one just stops the name from lying.
   */
  @Test
  void chunk_result_variants_exhaustive_switch() {
    ChunkResult r = new ChunkResult.ShortOfTarget("stopped short");
    String summary = switch (r) {
      case ChunkResult.Success s        -> "success:" + s.processed();
      case ChunkResult.PartialSuccess p -> "partial:" + p.skipped();
      case ChunkResult.ShortOfTarget s  -> "short:" + s.detail();
      case ChunkResult.Cancelled c      -> "cancelled";
      case ChunkResult.Failure f        -> "failed";
    };
    assertThat(summary).isEqualTo("short:stopped short");
    assertThat(new ChunkResult.PartialSuccess(48, 2, List.of(), ConnectorCursor.empty()).skipped())
        .isEqualTo(2);
  }

  @Test
  void read_result_variants_exhaustive_switch() {
    ReadResult<String> r = new ReadResult.HasMore<>(List.of("a","b"), ConnectorCursor.empty());
    String summary = switch (r) {
      case ReadResult.HasMore<String> hm     -> "more:" + hm.records().size();
      case ReadResult.EndOfStream<String> e  -> "end";
      case ReadResult.EmptyButOpen<String> o -> "short";
    };
    assertThat(summary).isEqualTo("more:2");
  }

  @Test
  void cursor_empty_and_not_empty() {
    assertThat(ConnectorCursor.empty().isEmpty()).isTrue();
    var c = Cursors.offset(10, 50);
    assertThat(c.isEmpty()).isFalse();
  }

  @Test
  void trigger_mode_enum_values() {
    assertThat(TriggerMode.values()).containsExactly(
        TriggerMode.POLL, TriggerMode.PUSH_HTTP, TriggerMode.PUSH_QUEUE, TriggerMode.MANUAL);
  }
}
