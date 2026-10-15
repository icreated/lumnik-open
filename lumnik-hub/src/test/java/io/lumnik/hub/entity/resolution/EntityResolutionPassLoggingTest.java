package io.lumnik.hub.entity.resolution;

import io.lumnik.hub.testsupport.LogProbe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntityResolutionPass#logged} in isolation — no CDI/Quarkus boot needed, since it only
 * touches its own {@code Logger} and the {@code PassSummary}/{@code runId} arguments it is
 * handed. Sergey's review on PR #645: moving the log line from the run hook into the pass itself
 * dropped {@code runId} from the message (two concurrent runs for one tenant became
 * indistinguishable), and the line stayed at INFO even when {@code providerFailures > 0} — the
 * one counter that should surface without grepping, same posture as {@code
 * ConnectorEngine.finalizeRun}'s WARN-for-Failed/Partial.
 */
class EntityResolutionPassLoggingTest {

  private static EntityResolutionPass.PassSummary summaryWith(long providerFailures) {
    return new EntityResolutionPass.PassSummary(1, 0, 0, 0, 0, 1, providerFailures, Map.of());
  }

  private static List<LogProbe.Line> capture(UUID runId, long providerFailures) {
    EntityResolutionPass pass = new EntityResolutionPass();
    return LogProbe.capture(EntityResolutionPass.class,
        () -> pass.logged("incremental", 1L, runId, summaryWith(providerFailures)));
  }

  @Test
  void the_run_id_appears_in_the_message_so_concurrent_runs_stay_distinguishable() {
    UUID runId = UUID.randomUUID();

    List<LogProbe.Line> lines = capture(runId, 0);

    assertThat(lines).anySatisfy(l -> assertThat(l.message()).contains(runId.toString()));
  }

  @Test
  void logs_at_info_when_there_are_no_provider_failures() {
    List<LogProbe.Line> lines = capture(UUID.randomUUID(), 0);

    assertThat(lines).anySatisfy(l -> assertThat(l.level()).isEqualTo(Level.INFO));
  }

  @Test
  void logs_at_warn_when_provider_failures_are_nonzero() {
    List<LogProbe.Line> lines = capture(UUID.randomUUID(), 2);

    assertThat(lines).anySatisfy(l -> {
      assertThat(l.level()).isEqualTo(Level.WARNING);
      assertThat(l.message()).contains("provider_failures=2");
    });
  }

  @Test
  void a_null_run_id_is_rendered_plainly_for_retry_and_replay() {
    List<LogProbe.Line> lines = capture(null, 0);

    assertThat(lines).anySatisfy(l -> assertThat(l.message()).contains("run=null"));
  }
}
