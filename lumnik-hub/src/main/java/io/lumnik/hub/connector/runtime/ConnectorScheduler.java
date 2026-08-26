package io.lumnik.hub.connector.runtime;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Cross-tenant scheduler: ticks every minute, and for each enabled connector with a cron
 * schedule, submits a run ONLY if the cron is actually due and no run is already in flight.
 *
 * <p>Uses the admin datasource (BYPASSRLS) so the query spans all tenants without a tenant
 * session variable. Cron uses the UNIX 5-field dialect — same as the {@code CronField} config
 * validator, the format connectors are validated against.
 *
 * <p><b>Endpoint scheduling decision (unified-engine Phase 1):</b> the scheduler still selects
 * due <em>connectors</em> at the connector-level cron and delegates to
 * {@link ConnectorEngine#submitAsync(java.util.UUID, RunTrigger)}, which now <em>fans out</em>
 * over the connector's enabled endpoints (each its own advisory-locked run). Per-endpoint cron
 * (the {@code ConnectorEndpoint.schedule} column) is deferred to a later lot; the in-flight
 * exclusion below is therefore still connector-keyed (a fast pre-filter — the engine's
 * per-endpoint advisory lock is the authoritative overlap guard).
 */
@ApplicationScoped
public class ConnectorScheduler {

  private static final Logger LOG = Logger.getLogger(ConnectorScheduler.class);

  private static final CronParser UNIX_PARSER =
      new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

  /**
   * Enabled, scheduled, live connectors that have NO run currently in flight, with the cron,
   * the connector's creation time, and the start time of its most recent run (NULL if never run).
   * The in-flight exclusion is a fast pre-filter; {@code ConnectorEngine}'s per-connector advisory
   * lock is the authoritative guard against overlap.
   */
  private static final String SELECT_DUE =
      "SELECT c.id, c.schedule, c.run_window, c.created_at, " +
      "       (SELECT MAX(r.started_at) FROM connector.connector_run r WHERE r.connector_id = c.id) AS last_started " +
      "FROM connector.connector c " +
      "WHERE c.enabled = true AND c.schedule IS NOT NULL AND c.deleted_at IS NULL " +
      "  AND NOT EXISTS (SELECT 1 FROM connector.connector_run r2 " +
      "                  WHERE r2.connector_id = c.id AND r2.status = 'Running')";

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject ConnectorEngine engine;

  @Scheduled(every = "1m", concurrentExecution = SKIP)
  void scheduledTick() {
    try {
      int n = tick();
      if (n > 0) LOG.infof("ConnectorScheduler: submitted %d runs", n);
    } catch (Exception e) {
      LOG.error("ConnectorScheduler.tick() failed; will retry in 1m", e);
    }
  }

  // Exposed for tests.
  public int tick() {
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    int submitted = 0;
    for (DueConnector c : listDueConnectors()) {
      Instant reference = c.lastStarted() != null ? c.lastStarted() : c.createdAt();
      if (!isCronDue(c.schedule(), reference, now)) continue;
      // P10: the execution window — a due slot outside it is SKIPPED, never queued
      // (the next in-window cron slot runs). Manual runs bypass this gate entirely.
      if (!RunWindow.contains(c.window(), now.toLocalTime())) {
        LOG.debugf("ConnectorScheduler: connector %s due but outside window %s — skipped", c.id(), c.window());
        continue;
      }
      try {
        engine.submitAsync(c.id(), new RunTrigger.Schedule(c.schedule()));
        submitted++;
      } catch (Exception e) {
        LOG.warnf("ConnectorScheduler: failed to submit connector %s: %s", c.id(), e.getMessage());
      }
    }
    return submitted;
  }

  /**
   * A connector is due when its cron has a scheduled firing instant at or before {@code now}
   * that is strictly after the {@code reference} (its last run, or its creation time if it has
   * never run). This fires each cron slot exactly once and never back-fills missed slots.
   * An unparseable cron is logged and treated as not-due (never silently spun).
   */
  static boolean isCronDue(String cron, Instant reference, ZonedDateTime now) {
    try {
      ExecutionTime et = ExecutionTime.forCron(UNIX_PARSER.parse(cron));
      Optional<ZonedDateTime> lastFire = et.lastExecution(now);
      return lastFire.isPresent() && lastFire.get().toInstant().isAfter(reference);
    } catch (Exception e) {
      LOG.warnf("Invalid cron '%s', treating as not-due: %s", cron, e.getMessage());
      return false;
    }
  }

  List<DueConnector> listDueConnectors() {
    List<DueConnector> result = new ArrayList<>();
    try (Connection conn = adminDs.getConnection();
         Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery(SELECT_DUE)) {
      while (rs.next()) {
        Timestamp last = rs.getTimestamp("last_started");
        result.add(new DueConnector(
            UUID.fromString(rs.getString("id")),
            rs.getString("schedule"),
            rs.getString("run_window"),
            rs.getTimestamp("created_at").toInstant(),
            last != null ? last.toInstant() : null));
      }
    } catch (Exception e) {
      throw new RuntimeException("ConnectorScheduler: listDueConnectors failed", e);
    }
    return result;
  }

  record DueConnector(UUID id, String schedule, String window, Instant createdAt, Instant lastStarted) {}
}
