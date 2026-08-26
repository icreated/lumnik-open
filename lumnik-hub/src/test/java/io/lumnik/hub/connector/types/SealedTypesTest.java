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

  @Test
  void chunk_result_variants() {
    ChunkResult ok = new ChunkResult.Success(50, ConnectorCursor.empty());
    ChunkResult.PartialSuccess p = new ChunkResult.PartialSuccess(48, 2, List.of(), ConnectorCursor.empty());
    assertThat(ok).isInstanceOf(ChunkResult.Success.class);
    assertThat(p.skipped()).isEqualTo(2);
  }

  @Test
  void read_result_generic() {
    ReadResult<String> r = new ReadResult.HasMore<>(List.of("a","b"), ConnectorCursor.empty());
    assertThat(r).isInstanceOf(ReadResult.HasMore.class);
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
