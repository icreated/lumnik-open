package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.RunStart;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ChunkExecutor;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Drives a {@code jdbc-generic} source through the engine the way the engine does — one run, chunk by chunk,
 * the writer landing rows in the hub — for the ITs that need real ingested tables (Oracle ingestion, #864's
 * cross-dialect join). Every call that needs the engine runs as the system, so a caller never has to know.
 *
 * <p>The hub-side reads ({@link #hubTable}, {@link #hubColumnsExact}, {@link #hubCount}) answer "what did the
 * writer make": the table is matched case-insensitively and then used exactly as spelled.
 */
@ApplicationScoped
public class JdbcRunDriver {

  public record Fixture(UUID runId, UUID connectorId, ConnectorEndpoint endpoint) {}

  public record Drained(int processed, int skipped, int chunks, ConnectorCursor cursor) {}

  @Inject ChunkExecutor exec;
  @Inject ConnectorTypeRegistry registry;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorRunRepository runs;
  @Inject UserTransaction utx;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  // ── the engine ──────────────────────────────────────────────────────────────

  private ConnectorType type() { return registry.find("jdbc-generic"); }

  /**
   * A connector, one enabled endpoint over {@code schema.table} and a running run. {@code dialectOrNull} null
   * leaves the dialect to URL inference; {@code watermarkOrNull} null means a full scan.
   */
  public Fixture fixture(String url, String user, String password, String dialectOrNull, String schema,
                         String table, String pk, String watermarkOrNull) {
    return TenantContext.callAsSystem(() -> inTx(() -> {
      Connector c = new Connector();
      c.setTenantId(1L);
      c.setName("jdbc-" + table.toLowerCase(Locale.ROOT) + "-" + System.nanoTime());
      c.setTypeId("jdbc-generic");
      Map<String, Object> connectorConfig = new HashMap<>(Map.of("url", url, "user", user, "password", password));
      if (dialectOrNull != null) connectorConfig.put("dialect", dialectOrNull);
      c.setConfig(connectorConfig);
      connectors.persist(c);
      connectors.flush();

      ConnectorEndpoint endpoint = new ConnectorEndpoint();
      endpoint.setTenantId(1L);
      endpoint.setConnectorId(c.getId());
      endpoint.setName(table);
      endpoint.setMethod("GET");
      endpoint.setPath("/" + table);
      endpoint.setEnabled(true);
      Map<String, Object> cfg = new HashMap<>(Map.of(
          "schema", schema, "name", table, "pk_columns", List.of(pk),
          "mode", watermarkOrNull == null ? "full" : "watermark"));
      if (watermarkOrNull != null) cfg.put("watermark_column", watermarkOrNull);
      endpoint.setConfig(cfg);
      endpoints.persist(endpoint);
      endpoints.flush();

      ConnectorRun run = new ConnectorRun();
      run.setTenantId(1L);
      run.setConnectorId(c.getId());
      run.setEndpointId(endpoint.getId());
      run.setStatus("Running");
      run.setSyncMode(watermarkOrNull == null ? "FullRefresh" : "Incremental");
      run.setStartedAt(Instant.now());
      run.setTriggeredBy("MANUAL");
      runs.persist(run);
      runs.flush();
      return new Fixture(run.getId(), c.getId(), endpoint);
    }));
  }

  public RunStart runStart(Fixture f, ConnectorCursor cursor) {
    return TenantContext.callAsSystem(() -> {
      ConnectorRun run = inTx(() -> runs.findById(f.runId()));
      Connector c = inTx(() -> connectors.findById(f.connectorId()));
      return exec.runStart(run, c, type(), f.endpoint(), cursor);
    });
  }

  /** Chunks until the stream ends, accumulating exactly what the engine's accounting would. */
  public Drained drain(Fixture f, ConnectorCursor start, RunStart runStart) {
    return TenantContext.callAsSystem(() -> {
      ConnectorCursor cursor = start;
      int processed = 0, skipped = 0, chunks = 0;
      for (int guard = 0; guard < 50; guard++) {
        ConnectorCursor at = cursor;
        ChunkResult r = inTx(() -> exec.executeChunk(runs.findById(f.runId()), type(), f.endpoint(), at, runStart.bound()));
        chunks++;
        switch (r) {
          case ChunkResult.Success s -> {
            processed += s.processed();
            cursor = s.nextCursor();
            if (s.endOfStream() || s.processed() == 0) return new Drained(processed, skipped, chunks, cursor);
          }
          case ChunkResult.PartialSuccess p -> {
            processed += p.processed();
            skipped += p.skipped();
            cursor = p.nextCursor();
          }
          default -> throw new AssertionError("unexpected chunk result: " + r);
        }
      }
      throw new AssertionError("runaway chunk loop");
    });
  }

  /** A whole run from {@code from}: its start, then its chunks. */
  public Drained drain(Fixture f, ConnectorCursor from) {
    return drain(f, from, runStart(f, from));
  }

  /** One full run from an empty cursor. */
  public Drained ingest(Fixture f) {
    return drain(f, ConnectorCursor.empty());
  }

  /** The first chunk, unbounded and uncaught: for the test that expects the engine to fail it. */
  public ChunkResult firstChunk(Fixture f) {
    return TenantContext.callAsSystem(
        () -> inTx(() -> exec.executeChunk(runs.findById(f.runId()), type(), f.endpoint(), ConnectorCursor.empty(), null)));
  }

  private <T> T inTx(Callable<T> work) throws Exception {
    utx.begin();
    try {
      T r = work.call();
      utx.commit();
      return r;
    } catch (Exception e) {
      try { utx.rollback(); } catch (Exception ignored) {}
      throw e;
    }
  }

  // ── what the hub holds ──────────────────────────────────────────────────────

  /** The hub table the writer made for a source table: matched case-insensitively, then used as spelled. */
  public String hubTable(String sourceTable) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'connector'"
             + " AND lower(table_name) = 't_" + sourceTable.toLowerCase(Locale.ROOT) + "'")) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  public long hubCount(String sourceTable) throws Exception {
    String t = hubTable(sourceTable);
    return t == null ? 0L : EventItSupport.scalar(adminDs, "SELECT count(*) FROM connector.\"" + t + "\"");
  }

  public List<String> hubColumnsExact(String hubTable) throws Exception {
    List<String> cols = new ArrayList<>();
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'connector'"
             + " AND table_name = '" + hubTable + "' ORDER BY ordinal_position")) {
      while (rs.next()) cols.add(rs.getString(1));
    }
    return cols;
  }
}
