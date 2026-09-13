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
import io.lumnik.hub.connector.domain.EffectiveConfig;
import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.repository.ConnectorCursorEntityRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.sql.SqlFailureText;

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
                                  ConnectorEndpoint endpoint, ConnectorCursor cursorIn,
                                  RunBound bound) {
    Prepared prepared = QuarkusTransaction.joiningExisting().call(() -> {
      Connector conn = connectors.findById(run.getConnectorId());
      ConnectorContext context = buildContext(run, conn, endpoint, bound);
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
            var outcome = writeOrPassThrough(writer, hm.records(), ctx);
            // A batch the writer refused wholesale is a chunk FAILURE, not a success with odd
            // counters: the rows would leave the conservation identity, nothing would reach the
            // dead-letter queue, and persistCursor below would advance the cursor past them —
            // contradicting this method's own javadoc.
            if (outcome instanceof WriteOutcome.AllFailed f) yield allFailed(c, f);
            persistCursor(c.getId(), endpoint.getId(), c.getTenantId(), hm.nextCursor());
            account(r, c, reader, writer, hm.records().size(), outcome);
            yield new ChunkResult.Success(hm.records().size(), hm.nextCursor(), false);
          }
          case ReadResult.EndOfStream eos -> {
            var outcome = writeOrPassThrough(writer, eos.records(), ctx);
            // See the HasMore branch. There is no cursor to hold back here — the stream is done —
            // but the counters must not move either.
            if (outcome instanceof WriteOutcome.AllFailed f) yield allFailed(c, f);
            account(r, c, reader, writer, eos.records().size(), outcome);
            yield new ChunkResult.Success(eos.records().size(), cursorIn, true);
          }
          case ReadResult.EmptyButOpen ep -> {
            // "Nothing usable this pass, the stream is still open" does not mean nothing was READ:
            // a window whose every line is unparsable yields no record and still buffers malformed
            // ones. Draining here is what keeps them out of the void.
            int malformed = captureMalformed(reader, c.getId(), r.getId());
            r.setRecordsIn(r.getRecordsIn() + malformed);
            r.setRecordsSkipped(r.getRecordsSkipped() + malformed);
            // A chunk ran. This branch bypasses account(), which is the only place the counter
            // moves, so every run ending here under-reported chunks= by one — in the end-of-run log
            // line, on the run row and in the TUI. Harmless while the shape had no producer.
            r.setChunkCount(r.getChunkCount() + 1);
            // The position IS persisted. A reader gets here having moved past the records it just
            // dead-lettered, and an advance the engine never stores is redone next run: the same
            // rows dead-lettered again, for ever. This branch persisted nothing while the shape had
            // no producer, which is the trap ChunkAccountingIT named — and #467 paid for the same
            // mistake on the EndOfStream path.
            // An EMPTY position says "no position", and writing a row to say that would be a row
            // that says nothing — it reads back exactly like the absent row, while touching
            // captured_at. Only a real advance is stored.
            if (!ep.position().isEmpty()) {
              persistCursor(c.getId(), endpoint.getId(), c.getTenantId(), ep.position());
            }
            // "Short of the target" needs a target, and THIS is the one place that rule lives. A
            // reader with no bound cannot be short of anything, so its empty-but-open pass ends the
            // run the way it always did. Asking each type to make that call instead put an engine
            // rule in a per-type ternary, and put it in two places.
            if (bound == null) yield new ChunkResult.Success(0, ep.position(), false);
            yield new ChunkResult.ShortOfTarget(
                "the reader stopped before this run's target (" + bound.describe() + ")"
                    + " — the next run resumes from the position this one reached");
          }
        };
      } catch (Throwable t) {
        // Swallowed here, INSIDE the transaction, exactly as before: the chunk turns into a
        // ChunkResult.Failure and the transaction commits whatever preceded the throw. Letting it
        // escape would instead mark the caller's transaction rollback-only.
        return failure(c, t);
      }
    });
  }

  /** A writerless target writes nothing and everything counts as written — the pass-through case. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static WriteOutcome writeOrPassThrough(ItemWriter writer, List records, ConnectorContext ctx) {
    return (writer != null) ? writer.write(records, ctx) : new WriteOutcome.AllWritten(records.size());
  }

  /**
   * THE CONSERVATION IDENTITY, in one place: records_in = records_out + records_skipped.
   *
   * <p>It used to live twice — once per non-empty read shape — and the two copies were identical
   * except for the cursor. That duplication is not academic: when the wholesale-refusal guard was
   * added it had to be written twice, and only one copy was covered until review caught it. A rule
   * spelled once cannot disagree with itself.
   *
   * <p>A read-time-malformed row counts on BOTH sides: it WAS read (records_in) and was NOT written
   * (records_skipped) — otherwise conservation breaks for a dead-lettered-at-read row (in=0, dlq=1).
   * Cell errors are a third tally, outside the row identity, drained exactly once: drainCellErrors()
   * empties on read, so a second call this chunk would return 0 and silently LOSE the count.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void account(ConnectorRun r, Connector c, ItemReader reader, ItemWriter writer,
                       int recordsRead, WriteOutcome outcome) {
    int malformed = captureMalformed(reader, c.getId(), r.getId());
    r.setRecordsIn(r.getRecordsIn() + recordsRead + malformed);
    r.setRecordsOut(r.getRecordsOut() + outcomeWritten(outcome));
    r.setRecordsSkipped(r.getRecordsSkipped()
        + outcomeSkipped(outcome, deadLetter, c.getId(), r.getId(), mapper)
        + malformed);
    r.setCellErrors(r.getCellErrors() + ((writer != null) ? writer.drainCellErrors() : 0));
    r.setChunkCount(r.getChunkCount() + 1);
  }

  /** Everything the chunk needs from the DB-bound preparation step, carried across the read. */
  private record Prepared(Connector connector, ConnectorContext ctx,
                          ItemReader<?> reader, ItemWriter<?> writer) {}

  /**
   * A wholesale write refusal. {@code AllFailed.cause()} may be null (the record permits it), so the
   * message is taken from {@code reason()} rather than from the throwable.
   */
  private static ChunkResult.Failure allFailed(Connector c, WriteOutcome.AllFailed f) {
    Logger.getLogger(ChunkExecutor.class)
        .warnf(f.cause(), "Chunk write refused every record for connector %s: %s", c.getName(), f.reason());
    return new ChunkResult.Failure(f.reason(), f.cause(), f.cause() instanceof TransientFailureException);
  }

  /**
   * The run's own account of why it stopped.
   *
   * <p>{@code getMessage()} alone loses whatever the cause said, and the cause is often the only
   * one that knows: a writer wrapper names the table, PostgreSQL names the column and the two
   * types that would not meet. {@link SqlFailureText#withCause} joins them under the rule that
   * decides which database messages may travel at all — the log above keeps every character
   * either way.
   */
  static ChunkResult.Failure failure(Connector c, Throwable t) {
    Logger.getLogger(ChunkExecutor.class)
        .warnf(t, "Chunk failure for connector %s: %s", c.getName(), t.getMessage());
    return new ChunkResult.Failure(
        SqlFailureText.withCause(t), t, t instanceof TransientFailureException);
  }

  /** Builds the per-run context for a single endpoint; the config rule lives in {@link EffectiveConfig}. */
  private ConnectorContext buildContext(ConnectorRun run, Connector c, ConnectorEndpoint endpoint,
                                        RunBound bound) {
    Map<String, Object> config = EffectiveConfig.of(c, endpoint);

    return new ConnectorContext(
        run.getId(), c.getId(), endpoint.getId(), c.getTenantId(),
        c.getName(), c.getTypeId(),
        config,
        c.getSyncMode(),
        Logger.getLogger("connector." + c.getName()),
        credentials, mappings,
        Clock.systemUTC(),
        bound);
  }

  /**
   * Asks the type what it learns about the source at run start. Called ONCE by the engine, before
   * the chunk loop and with no transaction, so the source round-trip inside does not hold a pooled
   * connection — the same rule step 2 of {@link #executeChunk} follows for the read.
   *
   * <p>The run and connector rows are PASSED, not re-read: {@link ConnectorEngine#runChunks} is
   * holding both when it calls this. The first version looked them both up again inside a
   * transaction of its own, which cost two selects and a transaction per run for rows already in
   * hand. Nothing here needs a fresh read — {@link #buildContext} uses the run's id and the
   * connector's own fields — so there is no transaction left to open.
   *
   * <p>The context handed to {@code runStart} carries no bound, which is correct and not a gap: it
   * is the context in which the bound is about to be measured. A credential the type resolves from
   * it is safe without a transaction here because {@code CredentialResolver} opens its own.
   *
   * <p>A type that cannot reach its source returns nothing rather than throwing — see
   * {@link ConnectorEngine#runChunks}. Measuring a target is a diagnostic; it must not be the thing
   * that turns a working ingestion into a failed run.
   */
  public RunStart runStart(ConnectorRun run, Connector c, ConnectorType type,
                           ConnectorEndpoint endpoint, ConnectorCursor cursor) {
    return type.runStart(buildContext(run, c, endpoint, null), cursor);
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
        // A declared skip is not a loss, so it is not dead-lettered. The rule lives on the type
        // (SkipReason.BENIGN_NAMES) — string-keyed in the repository's query, type-keyed here and
        // in DeadLetterCapture, one source for all three.
        if (!s.reason().benign()) {
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
