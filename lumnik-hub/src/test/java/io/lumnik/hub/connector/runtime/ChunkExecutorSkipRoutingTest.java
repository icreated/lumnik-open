package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.SkipReason;
import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Pins {@code WriteOutcome.PartialWrite}'s new shape end to end through {@link ChunkExecutor}:
 * every skip counts toward {@code records_skipped}, but only non-{@code PolicyExcluded} reasons
 * reach the DLQ, and the payload the DLQ receives is the {@code SkippedRecord}'s OWN payload —
 * not a positional pairing with the read batch (latent bug #2: {@code outcomeSkipped} used to
 * zip {@code skipped.get(i)} with {@code records.get(i)}, so a writer that appends one
 * {@code SkipReason} per failed row while iterating ALL rows could DLQ the wrong row).
 */
@QuarkusTest
class ChunkExecutorSkipRoutingTest {

  @Inject ChunkExecutor exec;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorRunRepository runs;
  @Inject ObjectMapper mapper;

  @InjectMock DeadLetterCapture deadLetter;

  private ConnectorRun run;
  private ConnectorEndpoint endpoint;

  private static final Map<String, Object> ROW_A = Map.of("k", "A");
  private static final Map<String, Object> ROW_B = Map.of("k", "B");
  private static final Map<String, Object> ROW_C = Map.of("k", "C");

  /**
   * A connector type whose writer always returns a fixed {@code PartialWrite}: row A dropped for
   * a quality reason (must reach the DLQ), row B dropped by policy (must NOT reach the DLQ).
   */
  static final class SkipRoutingType implements ConnectorType {
    @Override public String id() { return "skip-routing-probe"; }
    @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
    @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
    @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

    @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
      return (chunkSize, cursor, c) -> new ReadResult.EndOfStream<>(List.of(ROW_A, ROW_B));
    }

    @Override public ItemWriter<Map<String, Object>> writer(ConnectorContext ctx) {
      return (items, c) -> new WriteOutcome.PartialWrite(2, List.of(
          new WriteOutcome.SkippedRecord(ROW_A, new SkipReason.ValidationFailed(List.of("bad"))),
          new WriteOutcome.SkippedRecord(ROW_B, new SkipReason.PolicyExcluded("filter-where"))));
    }
  }

  /**
   * Same shape as {@link SkipRoutingType}, but the sole skip is a {@code Duplicate} — the other
   * benign reason in {@code ConnectorDeadLetterRepository.BENIGN_REASONS} alongside
   * {@code PolicyExcluded}. Must count toward {@code records_skipped} and must NEVER reach the DLQ.
   */
  static final class DuplicateSkipType implements ConnectorType {
    @Override public String id() { return "duplicate-skip-probe"; }
    @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
    @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
    @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

    @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
      return (chunkSize, cursor, c) -> new ReadResult.EndOfStream<>(List.of(ROW_C));
    }

    @Override public ItemWriter<Map<String, Object>> writer(ConnectorContext ctx) {
      return (items, c) -> new WriteOutcome.PartialWrite(0, List.of(
          new WriteOutcome.SkippedRecord(ROW_C, new SkipReason.Duplicate("dup-key-1"))));
    }
  }

  /**
   * A connector type whose writer always writes everything but reports cell-level parse
   * failures via {@link ItemWriter#drainCellErrors()} — the count of cells a mapping hook
   * nulled rather than dropping the row for. First {@link #writer} call reports 3, every
   * subsequent call reports 2, so two {@code executeChunk} calls against the same run pin
   * accumulation (3 then +2 = 5) rather than replacement.
   */
  static final class CellErrorProbeType implements ConnectorType {
    private int writerCalls = 0;

    @Override public String id() { return "cell-error-probe"; }
    @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
    @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
    @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

    @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
      return (chunkSize, cursor, c) -> new ReadResult.EndOfStream<>(List.of(ROW_A));
    }

    @Override public ItemWriter<Map<String, Object>> writer(ConnectorContext ctx) {
      long cellErrors = ++writerCalls == 1 ? 3 : 2;
      return new ItemWriter<Map<String, Object>>() {
        @Override public WriteOutcome write(List<Map<String, Object>> items, ConnectorContext c) {
          return new WriteOutcome.AllWritten(items.size());
        }
        @Override public long drainCellErrors() { return cellErrors; }
      };
    }
  }

  @BeforeEach
  void seed() {
    TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("skip-routing-" + UUID.randomUUID());
      c.setTypeId("skip-routing-probe");
      c.setConfig(Map.of());
      connectors.persist(c);
      connectors.flush();

      ConnectorEndpoint ep = new ConnectorEndpoint();
      ep.setTenantId(1L);
      ep.setConnectorId(c.getId());
      ep.setName("default");
      ep.setMethod("GET");
      ep.setPath("/default");
      ep.setEnabled(true);
      ep.setConfig(Map.of());
      endpoints.persist(ep);
      endpoints.flush();

      ConnectorRun r = new ConnectorRun();
      r.setTenantId(1L);
      r.setConnectorId(c.getId());
      r.setEndpointId(ep.getId());
      r.setStatus("Running");
      r.setSyncMode("Incremental");
      r.setStartedAt(Instant.now());
      r.setTriggeredBy("MANUAL");
      runs.persist(r);
      runs.flush();

      endpoint = ep;
      run = r;
    }));
  }

  private ConnectorRun persistedRun() {
    return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
      runs.getEntityManager().clear();
      return runs.findById(run.getId());
    }));
  }

  @Test
  void every_skip_counts_but_only_quality_failures_reach_the_dlq() {
    SkipRoutingType type = new SkipRoutingType();

    ChunkResult result = TenantContext.callAsSystem(
        () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty()));

    assertThat(result).isInstanceOf(ChunkResult.Success.class);

    ConnectorRun after = persistedRun();
    assertThat(after.getRecordsOut()).as("written=2 from the PartialWrite").isEqualTo(2);
    assertThat(after.getRecordsSkipped()).as("both skips count, policy-excluded included").isEqualTo(2);

    ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
    ArgumentCaptor<SkipReason> reason = ArgumentCaptor.forClass(SkipReason.class);
    verify(deadLetter, times(1))
        .capture(eq(run.getConnectorId()), eq(run.getId()), payload.capture(), reason.capture());

    assertThat(payload.getValue())
        .as("the DLQ payload must be row A's own payload, not a positionally-paired row")
        .isEqualTo(mapper.valueToTree(ROW_A));
    assertThat(reason.getValue()).isInstanceOf(SkipReason.ValidationFailed.class);
  }

  /**
   * Aligns the DLQ exemption with {@code ConnectorDeadLetterRepository.BENIGN_REASONS}: Duplicate
   * is as benign as PolicyExcluded — a {@code PartialWrite} skip for that reason must count toward
   * {@code records_skipped} but never reach the DLQ.
   */
  @Test
  void duplicate_skip_counts_but_never_reaches_the_dlq() {
    DuplicateSkipType type = new DuplicateSkipType();

    ChunkResult result = TenantContext.callAsSystem(
        () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty()));

    assertThat(result).isInstanceOf(ChunkResult.Success.class);

    ConnectorRun after = persistedRun();
    assertThat(after.getRecordsOut()).as("written=0 from the PartialWrite").isEqualTo(0);
    assertThat(after.getRecordsSkipped()).as("the duplicate skip is counted").isEqualTo(1);

    verify(deadLetter, times(0)).capture(eq(run.getConnectorId()), eq(run.getId()), ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  /**
   * Pins accumulation, not replacement: a run that already carries {@code cellErrors == 3} from
   * one chunk must read {@code 5} after a second chunk's writer reports 2 more — never 2.
   */
  @Test
  void cell_errors_accumulate_across_chunks() {
    CellErrorProbeType type = new CellErrorProbeType();

    ChunkResult first = TenantContext.callAsSystem(
        () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty()));
    assertThat(first).isInstanceOf(ChunkResult.Success.class);
    assertThat(persistedRun().getCellErrors()).as("first chunk reports 3 cell errors").isEqualTo(3);

    ChunkResult second = TenantContext.callAsSystem(
        () -> exec.executeChunk(run, type, endpoint, ConnectorCursor.empty()));
    assertThat(second).isInstanceOf(ChunkResult.Success.class);
    assertThat(persistedRun().getCellErrors())
        .as("second chunk's 2 cell errors accumulate onto the first chunk's 3")
        .isEqualTo(5);
  }
}
