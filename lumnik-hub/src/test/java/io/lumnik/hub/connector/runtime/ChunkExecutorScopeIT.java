package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.MalformedRecord;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.api.types.RunStart;
import io.lumnik.hub.connector.api.types.TriggerMode;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #898: the run scope reaches a reader on {@code ctx.scope()}. A caller with no run around it (the old
 * five-argument {@code executeChunk} / {@code runStart}) gets a scope that lives exactly one call; a caller that
 * passes its own owns the lifetime.
 */
@QuarkusTest
class ChunkExecutorScopeIT {

  @Inject ChunkExecutor exec;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorRunRepository runs;
  @Inject ConnectorCursorEntityRepository cursors;

  /** What a transport would hold: counts its closes. */
  private static final class Held implements AutoCloseable {
    boolean closed;
    @Override public void close() { closed = true; }
  }

  /** A type whose reader and run start ask the scope for one resource and remember what they saw. */
  private static class ScopeType implements ConnectorType {
    final List<Held> seenByReader = new ArrayList<>();
    final List<Held> seenByRunStart = new ArrayList<>();
    final List<Object> scopes = new ArrayList<>();

    @Override public String id() { return "scope-probe"; }
    @Override public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }
    @Override public Set<TriggerMode> supportedTriggerModes() { return Set.of(TriggerMode.MANUAL); }
    @Override public void validateConfig(Map<String, Object> config) { /* nothing to validate */ }

    @Override public RunStart runStart(ConnectorContext ctx, ConnectorCursor cursor) {
      seenByRunStart.add(ctx.scope().resource("probe", Held::new));
      return RunStart.none();
    }

    @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
      scopes.add(ctx.scope());
      return (chunkSize, cursor, c) -> {
        seenByReader.add(c.scope().resource("probe", Held::new));
        return new ReadResult.EndOfStream<>(List.of());
      };
    }
  }

  private record Rows(Connector connector, ConnectorEndpoint endpoint, ConnectorRun run) {}

  private Rows seed() {
    Connector c = new Connector();
    c.setTenantId(1L);
    c.setName("scope-it");
    c.setTypeId("scope-probe");
    c.setConfig(Map.of());
    connectors.persist(c);
    connectors.flush();

    ConnectorEndpoint endpoint = new ConnectorEndpoint();
    endpoint.setTenantId(1L);
    endpoint.setConnectorId(c.getId());
    endpoint.setName("default");
    endpoint.setMethod("GET");
    endpoint.setPath("/default");
    endpoint.setEnabled(true);
    endpoint.setConfig(Map.of());
    endpoints.persist(endpoint);
    endpoints.flush();

    ConnectorRun run = new ConnectorRun();
    run.setTenantId(1L);
    run.setConnectorId(c.getId());
    run.setEndpointId(endpoint.getId());
    run.setStatus("Running");
    run.setSyncMode("Incremental");
    run.setStartedAt(Instant.now());
    run.setTriggeredBy("MANUAL");
    runs.persist(run);
    runs.flush();
    return new Rows(c, endpoint, run);
  }

  @Test
  @TestTransaction
  void the_old_five_argument_executeChunk_gives_the_reader_a_scope_that_lives_exactly_one_chunk() {
    TenantContext.runAsSystem(() -> {
      Rows r = seed();
      ScopeType type = new ScopeType();

      exec.executeChunk(r.run(), type, r.endpoint(), ConnectorCursor.empty(), null);
      exec.executeChunk(r.run(), type, r.endpoint(), ConnectorCursor.empty(), null);

      assertThat(type.seenByReader).hasSize(2);
      assertThat(type.seenByReader.get(0)).as("one chunk, one scope: nothing is shared across calls")
          .isNotSameAs(type.seenByReader.get(1));
      assertThat(type.seenByReader).as("each scope was closed when its call returned")
          .allSatisfy(h -> assertThat(h.closed).isTrue());
    });
  }

  @Test
  @TestTransaction
  void a_scope_passed_in_is_the_one_the_reader_sees_and_is_not_closed_by_the_chunk() {
    TenantContext.runAsSystem(() -> {
      Rows r = seed();
      ScopeType type = new ScopeType();
      OwnedRunScope scope = new OwnedRunScope("it");

      exec.executeChunk(r.run(), type, r.endpoint(), ConnectorCursor.empty(), null, scope);
      exec.executeChunk(r.run(), type, r.endpoint(), ConnectorCursor.empty(), null, scope);

      assertThat(type.scopes).hasSize(2).allSatisfy(s -> assertThat(s).isSameAs(scope));
      assertThat(type.seenByReader.get(1)).as("the second chunk meets the first chunk's resource")
          .isSameAs(type.seenByReader.get(0));
      assertThat(type.seenByReader.get(0).closed).as("the chunk does not own the lifetime").isFalse();

      scope.close();
      assertThat(type.seenByReader.get(0).closed).isTrue();
    });
  }

  @Test
  @TestTransaction
  void the_old_runStart_hands_the_type_a_scope_that_is_closed_when_it_returns() {
    TenantContext.runAsSystem(() -> {
      Rows r = seed();
      ScopeType type = new ScopeType();

      exec.runStart(r.run(), r.connector(), type, r.endpoint(), ConnectorCursor.empty());

      assertThat(type.seenByRunStart).hasSize(1);
      assertThat(type.seenByRunStart.get(0).closed).isTrue();
    });
  }

  /** A type whose reader ends the stream with a final cursor; {@code accountingFails} makes the chunk's accounting throw. */
  private static ConnectorType endsWithFinalCursor(boolean accountingFails) {
    return new ScopeType() {
      @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
        return new ItemReader<>() {
          @Override public ReadResult<Map<String, Object>> read(int chunkSize, ConnectorCursor cursor, ConnectorContext c) {
            var node = new ObjectMapper().createObjectNode().put("file", "last.csv");
            return new ReadResult.EndOfStream<>(List.of(), new ConnectorCursor(node, Instant.now()));
          }

          @Override public List<MalformedRecord> drainMalformed() {
            if (accountingFails) throw new IllegalStateException("accounting failed");
            return List.of();
          }
        };
      }
    };
  }

  @Test
  @TestTransaction
  void an_end_of_stream_final_cursor_is_stored() {
    TenantContext.runAsSystem(() -> {
      Rows r = seed();

      exec.executeChunk(r.run(), endsWithFinalCursor(false), r.endpoint(), ConnectorCursor.empty(), null);

      assertThat(cursors.findByEndpoint(r.endpoint().getId())).isNotNull();
    });
  }

  @Test
  @TestTransaction
  void a_final_cursor_is_not_stored_when_the_chunks_accounting_fails() {
    TenantContext.runAsSystem(() -> {
      Rows r = seed();

      var result = exec.executeChunk(r.run(), endsWithFinalCursor(true), r.endpoint(), ConnectorCursor.empty(), null);

      assertThat(result).isInstanceOf(ChunkResult.Failure.class);
      assertThat(cursors.findByEndpoint(r.endpoint().getId())).as("a failed chunk leaves the position alone").isNull();
    });
  }

  /** A type whose reader hands back a HasMore with a next cursor; {@code accountingFails} makes the chunk's accounting throw. */
  private static ConnectorType hasMoreWithCursor(boolean accountingFails) {
    return new ScopeType() {
      @Override public ItemReader<Map<String, Object>> reader(ConnectorContext ctx) {
        return new ItemReader<>() {
          @Override public ReadResult<Map<String, Object>> read(int chunkSize, ConnectorCursor cursor, ConnectorContext c) {
            var node = new ObjectMapper().createObjectNode().put("file", "next.csv");
            return new ReadResult.HasMore<>(List.of(), new ConnectorCursor(node, Instant.now()));
          }

          @Override public List<MalformedRecord> drainMalformed() {
            if (accountingFails) throw new IllegalStateException("accounting failed");
            return List.of();
          }
        };
      }
    };
  }

  @Test
  @TestTransaction
  void a_has_more_next_cursor_is_stored() {
    TenantContext.runAsSystem(() -> {
      Rows r = seed();

      exec.executeChunk(r.run(), hasMoreWithCursor(false), r.endpoint(), ConnectorCursor.empty(), null);

      assertThat(cursors.findByEndpoint(r.endpoint().getId())).isNotNull();
    });
  }

  @Test
  @TestTransaction
  void a_has_more_cursor_is_not_stored_when_the_chunks_accounting_fails() {
    TenantContext.runAsSystem(() -> {
      Rows r = seed();

      var result = exec.executeChunk(r.run(), hasMoreWithCursor(true), r.endpoint(), ConnectorCursor.empty(), null);

      assertThat(result).isInstanceOf(ChunkResult.Failure.class);
      assertThat(cursors.findByEndpoint(r.endpoint().getId())).as("a failed chunk leaves the position alone").isNull();
    });
  }
}
