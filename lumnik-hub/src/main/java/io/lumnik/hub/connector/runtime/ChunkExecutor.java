package io.lumnik.hub.connector.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.helpers.CredentialResolver;
import io.lumnik.hub.connector.api.helpers.MappingResolver;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCursorEntity;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ChunkExecutor {

  @Inject ConnectorRepository connectors;
  @Inject ConnectorRunRepository runs;
  @Inject ConnectorCursorEntityRepository cursors;
  @Inject CredentialResolver credentials;
  @Inject MappingResolver mappings;
  @Inject DeadLetterCapture deadLetter;
  @Inject ObjectMapper mapper;

  /**
   * Runs one chunk: read from the source, then write + advance the cursor + update the run
   * counters.
   *
   * <p><b>Transaction shape.</b> The chunk used to be ONE {@code @Transactional} method, so a
   * pooled {@code default} connection — and its {@code SET LOCAL app.current_tenant} — was held
   * across {@code reader.read(...)}, a network/file round-trip to the customer's legacy source.
   * Under real ingestion that exhausted the 20-slot Agroal pool. It is now two SHORT transactions
   * with the source round-trip between them:
   * <ol>
   *   <li>load the connector row and build the reader/writer,</li>
   *   <li>— read, with no transaction and no connection held —</li>
   *   <li>— {@code writer.prepare(records, ctx)}, same no-tx/no-connection zone —</li>
   *   <li>write, persist the cursor and update the counters, together.</li>
   * </ol>
   *
   * <p>Step 3, {@code prepare}, is the hook a writer with its own network round-trip (e.g.
   * RagWriter's {@code embedAll}) can use to do that work before the write transaction opens,
   * instead of holding a pooled, tenant-scoped connection idle across it — no-op by default, so
   * every {@code ItemWriter} that has nothing to prepare is unaffected. A {@code prepare}
   * failure is caught in this same no-tx zone, exactly like a read failure: it turns into a
   * {@link ChunkResult.Failure} and the write transaction never opens, so the cursor cannot
   * advance.
   *
   * <p>Step 1 keeps {@code type.reader/writer(ctx)} inside the transaction on purpose: building a
   * reader can hit the DB (jdbc-generic resolves its credential through {@code CredentialResolver}),
   * and without a JTA transaction the Agroal interceptor never sets {@code app.current_tenant}, so
   * an RLS-enforced datasource returns nothing.
   *
   * <p><b>Atomicity is unchanged.</b> Step 3 is a single transaction, in the same order as before:
   * the write happens first and the cursor is persisted only after it returns, so a cursor can
   * never advance past rows that were never written. {@code joiningExisting()} keeps a caller that
   * already holds a transaction (the ITs) on exactly the old single-transaction behaviour.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public ChunkResult executeChunk(ConnectorRun run, ConnectorType type,
                                  ConnectorEndpoint endpoint, ConnectorCursor cursorIn) {
    Prepared prepared = QuarkusTransaction.joiningExisting().call(() -> {
      Connector conn = connectors.findById(run.getConnectorId());
      ConnectorContext context = buildContext(run, conn, endpoint);
      return new Prepared(conn, context, type.reader(context), type.writer(context));
    });
    Connector c = prepared.connector();
    ConnectorContext ctx = prepared.ctx();
    ItemReader reader = prepared.reader();
    ItemWriter writer = prepared.writer();

    ReadResult result;
    try {
      // OUTSIDE any transaction — this is the source round-trip.
      result = reader.read(type.defaultChunkSize(), cursorIn, ctx);
    } catch (Throwable t) {
      return failure(c, t);
    }

    List<?> recordsRead = switch (result) {
      case ReadResult.HasMore hm -> hm.records();
      case ReadResult.EndOfStream eos -> eos.records();
      case ReadResult.EmptyButOpen ep -> List.of();
    };
    if (writer != null && !recordsRead.isEmpty()) {
      try {
        // STILL OUTSIDE any transaction — a writer's own network round-trip (e.g. RagWriter's
        // embedAll) belongs here, not inside the write transaction below.
        writer.prepare(recordsRead, ctx);
      } catch (Throwable t) {
        return failure(c, t);
      }
    }

    return QuarkusTransaction.joiningExisting().call(() -> {
      try {
        // Re-load the run INSIDE this transaction so the counter mutations below are dirty-checked
        // and flushed by its commit — the instance the caller handed us was loaded by an earlier,
        // already-ended transaction. A caller that still holds one gets the very same managed
        // instance back, so nothing changes for it.
        ConnectorRun r = runs.findById(run.getId());
        if (r == null) return new ChunkResult.Failure("Run not found: " + run.getId(), null, false);

        return switch (result) {
          case ReadResult.HasMore hm -> {
            var outcome = (writer != null) ? writer.write(hm.records(), ctx) : new WriteOutcome.AllWritten(hm.records().size());
            persistCursor(c.getId(), endpoint.getId(), c.getTenantId(), hm.nextCursor());
            // A read-time-malformed row was read from the source, so it counts toward
            // recordsIn as well as recordsSkipped — otherwise conservation (in = out +
            // skipped) breaks for a dead-lettered-at-read row (in=0, dlq=1).
            int malformed = captureMalformed(reader, c.getId(), r.getId());
            r.setRecordsIn(r.getRecordsIn() + hm.records().size() + malformed);
            r.setRecordsOut(r.getRecordsOut() + outcomeWritten(outcome));
            r.setRecordsSkipped(r.getRecordsSkipped()
                + outcomeSkipped(outcome, deadLetter, c.getId(), r.getId(), mapper)
                + malformed);
            // Drain exactly once: drainCellErrors() empties on read, so a second call this chunk
            // would return 0 and silently LOSE the count rather than double it.
            long cellErrors = (writer != null) ? writer.drainCellErrors() : 0;
            r.setCellErrors(r.getCellErrors() + cellErrors);
            r.setChunkCount(r.getChunkCount() + 1);
            yield new ChunkResult.Success(hm.records().size(), hm.nextCursor());
          }
          case ReadResult.EndOfStream eos -> {
            var outcome = (writer != null) ? writer.write(eos.records(), ctx) : new WriteOutcome.AllWritten(eos.records().size());
            // See the HasMore branch: malformed rows were read, so they count toward
            // recordsIn (conservation) as well as recordsSkipped.
            int malformed = captureMalformed(reader, c.getId(), r.getId());
            r.setRecordsIn(r.getRecordsIn() + eos.records().size() + malformed);
            r.setRecordsOut(r.getRecordsOut() + outcomeWritten(outcome));
            r.setRecordsSkipped(r.getRecordsSkipped()
                + outcomeSkipped(outcome, deadLetter, c.getId(), r.getId(), mapper)
                + malformed);
            // Drain exactly once: drainCellErrors() empties on read, so a second call this chunk
            // would return 0 and silently LOSE the count rather than double it.
            long cellErrors = (writer != null) ? writer.drainCellErrors() : 0;
            r.setCellErrors(r.getCellErrors() + cellErrors);
            r.setChunkCount(r.getChunkCount() + 1);
            yield new ChunkResult.Success(eos.records().size(), cursorIn);
          }
          case ReadResult.EmptyButOpen ep -> new ChunkResult.Success(0, ep.sameCursor());
        };
      } catch (Throwable t) {
        // Swallowed here, INSIDE the transaction, exactly as before: the chunk turns into a
        // ChunkResult.Failure and the transaction commits whatever preceded the throw. Letting it
        // escape would instead mark the caller's transaction rollback-only.
        return failure(c, t);
      }
    });
  }

  /** Everything the chunk needs from the DB-bound preparation step, carried across the read. */
  private record Prepared(Connector connector, ConnectorContext ctx,
                          ItemReader<?> reader, ItemWriter<?> writer) {}

  private static ChunkResult.Failure failure(Connector c, Throwable t) {
    Logger.getLogger(ChunkExecutor.class)
        .warnf(t, "Chunk failure for connector %s: %s", c.getName(), t.getMessage());
    return new ChunkResult.Failure(t.getMessage(), t, t instanceof TransientFailureException);
  }

  /**
   * Builds the per-run context for a single endpoint.
   *
   * <p>Config is the connection-level config merged with the endpoint config, the endpoint
   * winning on key conflicts. The connection base is {@code config.get("connection")} when the
   * connector carries an explicit connection block (declarative manifests), otherwise the whole
   * flat connector config (legacy / synthetic endpoints).
   */
  @SuppressWarnings("unchecked")
  private ConnectorContext buildContext(ConnectorRun run, Connector c, ConnectorEndpoint endpoint) {
    Map<String, Object> base = (c.getConfig().get("connection") instanceof Map<?, ?> conn)
        ? (Map<String, Object>) conn
        : c.getConfig();

    Map<String, Object> config = new HashMap<>(base);
    if (endpoint.getConfig() != null) config.putAll(endpoint.getConfig());
    if (c.getCredentialId() != null) config.put("_credentialId", c.getCredentialId().toString());

    return new ConnectorContext(
        run.getId(), c.getId(), endpoint.getId(), c.getTenantId(),
        c.getName(), c.getTypeId(),
        config,
        c.getSyncMode(),
        Logger.getLogger("connector." + c.getName()),
        credentials, mappings,
        Clock.systemUTC());
  }

  private void persistCursor(UUID connectorId, UUID endpointId, Long tenantId, ConnectorCursor newCursor) {
    var ent = cursors.findByEndpoint(endpointId);
    if (ent == null) {
      ent = new ConnectorCursorEntity();
      ent.setTenantId(tenantId);
      ent.setConnectorId(connectorId);
      ent.setEndpointId(endpointId);
      ent.setCursorValue(newCursor.value());
      ent.setCapturedAt(Instant.now());
      cursors.persist(ent);
    } else {
      ent.setCursorValue(newCursor.value());
      ent.setCapturedAt(Instant.now());
    }
  }

  private static int outcomeWritten(WriteOutcome o) {
    return switch (o) {
      case WriteOutcome.AllWritten a   -> a.count();
      case WriteOutcome.PartialWrite p -> p.written();
      case WriteOutcome.AllFailed f    -> 0;
    };
  }

  private static int outcomeSkipped(WriteOutcome o, DeadLetterCapture dlq, UUID connectorId, UUID runId,
                                    ObjectMapper mapper) {
    if (o instanceof WriteOutcome.PartialWrite p) {
      for (var s : p.skipped()) {
        // Mirrors ConnectorDeadLetterRepository.BENIGN_REASONS — the authority for what counts
        // as a benign, declared skip rather than a loss (also guarded independently in
        // DeadLetterCapture.capture's metric exemption). That set is string-keyed against the
        // persisted skip-reason name, so it isn't cheaply shareable with this SkipReason-typed
        // check; keep both conditions in sync by hand if a third benign reason is ever added.
        if (!(s.reason() instanceof SkipReason.PolicyExcluded) && !(s.reason() instanceof SkipReason.Duplicate)) {
          dlq.capture(connectorId, runId, mapper.valueToTree(s.payload()), s.reason());
        }
      }
      return p.skipped().size();
    }
    return 0;
  }

  /**
   * Drains malformed source rows the reader isolated during this read pass and routes each to the
   * dead-letter queue, within the chunk transaction. Returns the count (added to recordsSkipped).
   *
   * <p>{@code drainMalformed()} does not depend on the reader's element type, so {@code ItemReader<?>}
   * yields a properly typed {@code List<MalformedRecord>} — no raw type, no cast.
   */
  private int captureMalformed(ItemReader<?> reader, UUID connectorId, UUID runId) {
    List<MalformedRecord> malformed = reader.drainMalformed();
    for (MalformedRecord m : malformed) {
      Map<String, Object> payload = new HashMap<>();
      payload.put("file", m.file());
      payload.put("line", m.line());
      payload.put("raw", m.rawValues());
      deadLetter.capture(connectorId, runId, mapper.valueToTree(payload),
          new SkipReason.MalformedSource(m.file(), m.line(), m.message()));
    }
    return malformed.size();
  }
}
