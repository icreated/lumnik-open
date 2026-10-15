package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.api.types.RunStatus;
import io.lumnik.hub.connector.api.types.RunTrigger;
import io.lumnik.hub.connector.event.RunTableStats;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.connector.runtime.ConnectorEngine;
import io.lumnik.hub.connector.runtime.SchemaRefreshSpy;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * #904 against the real engine and the real async hook: a run's incremental resolution reads only the
 * sources whose table that run wrote, and two connectors writing ONE hub table each get their rows
 * resolved.
 *
 * <p>The second half is why the run names its tables rather than the pass asking {@code
 * connector.dynamic_table} who owns a source: a hub table is named after the source table, so two
 * connectors reading a table of the same name write into the same {@code connector.t_<name>}
 * (docs/connectors/jdbc.md, "The hub table is named after the source table") — and the registry keeps
 * ONE row per mapping, naming whichever connector registered it. Two csv-file endpoints with the same
 * {@code name} share a mapping id exactly as two jdbc endpoints over a same-named table do.
 *
 * <p>The entity's other source is a table that does not exist: reading it fails the entity's pass, and
 * a failed entity is never covered (#748). A covered run therefore proves that source was not read.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EntityResolutionRunHookTablesIT {

  private static final long TENANT = 1L;

  @Inject ConnectorEngine engine;
  @Inject ConnectorRepository connectors;
  @Inject ConnectorEndpointRepository endpoints;
  @Inject ConnectorRunRepository runs;
  @Inject EntityRegistry registry;
  @Inject UserTransaction utx;
  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject SchemaRefreshSpy runCompletedSpy;

  @Test
  void a_run_reads_only_the_sources_it_wrote_and_both_writers_of_a_shared_table_are_resolved() throws Exception {
    long n = System.nanoTime();
    String shared = "res904_shared_" + n;
    String unwritten = "t_res904_unwritten_" + n;   // never created: reading it fails
    String entity = "ResHook904x" + n;
    registry.put(new EntityManifestParser().parse("""
        kind: Entity
        name: %s
        sources:
          - table: %s
            map:
              CUSTOMER_ID: customer_id
              LABEL_COL: label
          - table: t_%s
            map:
              row_id: customer_id
              label: label
        resolution:
          identity:
            %s: customer_id
            t_%s: customer_id
          cascade:
            - id: label
              exact: { fields: [label] }
        """.formatted(entity, unwritten, shared, unwritten, shared)));

    try {
      runBothAndAwait(n, entity, shared);
    } finally {
      registry.remove(entity);   // the JVM-wide registry: never leave an entity whose source cannot be read
    }
  }

  private void runBothAndAwait(long n, String entity, String shared) throws Exception {
    UUID a = csvConnector("res904-a-" + n, shared, "row_id,label\n1,alpha-" + n + "\n2,beta-" + n + "\n");
    UUID b = csvConnector("res904-b-" + n, shared, "row_id,label\n3,gamma-" + n + "\n4,delta-" + n + "\n");
    assertThat(engine.runSync(a, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
    assertThat(engine.runSync(b, new RunTrigger.Manual(1L))).isInstanceOf(RunStatus.Completed.class);
    UUID runA = newestRun(a);
    UUID runB = newestRun(b);

    assertThat(registryOwners("t_" + shared))
        .as("the registry names ONE owner for the shared table — asking it would skip the other connector")
        .containsExactly(a);

    String entityType = entity.toLowerCase(Locale.ROOT);
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      assertThat(covered(entityType, runA)).as("run A covered: the source it did not write was not read").isTrue();
      assertThat(covered(entityType, runB)).as("run B covered: the source it did not write was not read").isTrue();
      assertThat(resolvedIds(entityType, "t_" + shared))
          .as("both connectors' rows of the shared table resolved")
          .containsExactlyInAnyOrder("1", "2", "3", "4");
    });
  }

  /**
   * Review on PR #951: when another ending (the reaper) finalizes a run before the run's own finish,
   * it also drains the run's {@link RunTableStats}. The finish that lost that race still announces the
   * run, and an empty table list there would skip every source while covering every entity — the
   * run's rows never resolved. The loser must announce "unknown".
   *
   * <p>The reap is staged inside the run's first write — {@code reapRun} joins the chunk's transaction,
   * so the row commits Failed with that chunk — which leaves the run's own finish exactly the state a
   * real race leaves it: the row no longer Running, the stats already drained.
   */
  @Test
  void a_run_whose_finish_lost_the_race_to_another_ending_reads_every_source() throws Exception {
    long n = System.nanoTime();
    String table = "res904_reaped_" + n;
    String entity = "ResHook904r" + n;
    registry.put(new EntityManifestParser().parse("""
        kind: Entity
        name: %s
        sources:
          - table: t_%s
            map:
              row_id: customer_id
              label: label
        resolution:
          identity:
            t_%s: customer_id
          cascade:
            - id: label
              exact: { fields: [label] }
        """.formatted(entity, table, table)));
    UUID connector = csvConnector("res904-r-" + n, table, "row_id,label\n1,alpha-" + n + "\n2,beta-" + n + "\n");
    AtomicBoolean reaped = new AtomicBoolean();
    QuarkusMock.installMockForType(new RunTableStats() {
      @Override
      public void recordCounts(UUID runId, String t, int written, int skipped) {
        super.recordCounts(runId, t, written, skipped);
        if (runId != null && reaped.compareAndSet(false, true)) {
          engine.reapRun(runId, TENANT, "reaped by EntityResolutionRunHookTablesIT", Instant.now());
        }
      }
    }, RunTableStats.class);
    try {
      engine.runSync(connector, new RunTrigger.Manual(1L));
      UUID run = newestRun(connector);
      assertThat(reaped).as("test setup: another ending won the race").isTrue();

      String entityType = entity.toLowerCase(Locale.ROOT);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        assertThat(runCompletedSpy.saw(run)).as("the losing finish still announced the run").isTrue();
        assertThat(resolvedIds(entityType, "t_" + table))
            .as("the run's rows resolved: its finish lost the race, so its table list is unknown")
            .containsExactlyInAnyOrder("1", "2");
      });
    } finally {
      registry.remove(entity);
    }
  }

  /** A csv-file connector whose one endpoint lands {@code csv} in {@code connector.t_<name>}. */
  private UUID csvConnector(String connectorName, String name, String csv) throws Exception {
    Path file = Files.createTempFile("res904_", ".csv");
    file.toFile().deleteOnExit();
    Files.writeString(file, csv);
    String path = file.toAbsolutePath().toString();
    return runInTxAsSystem(utx, () -> {
      Map<String, Object> cfg = new HashMap<>();
      cfg.put("kind", "csv-file");
      cfg.put("transport", Map.of("kind", "local", "path", path));
      cfg.put("parser", Map.of("has_header", true));
      Connector c = new Connector();
      c.setTenantId(TENANT);
      c.setName(connectorName);
      c.setTypeId("csv-file");
      c.setConfig(cfg);
      connectors.persist(c);
      connectors.flush();
      ConnectorEndpoint ep = new ConnectorEndpoint();
      ep.setTenantId(TENANT);
      ep.setConnectorId(c.getId());
      ep.setName("default");
      ep.setMethod("GET");
      ep.setPath(path);
      ep.setEnabled(true);
      ep.setConfig(Map.of("name", name));
      endpoints.persist(ep);
      endpoints.flush();
      return c.getId();
    });
  }

  private UUID newestRun(UUID connectorId) {
    return runInTxAsSystem(utx, () -> runs.findRecentActiveByConnector(connectorId, 1).get(0).getId());
  }

  private List<UUID> registryOwners(String table) {
    return Jdbc.query(adminDs, "registryOwners",
        "SELECT connector_id FROM connector.dynamic_table WHERE tenant_id = ? AND table_name = ?",
        ps -> { ps.setLong(1, TENANT); ps.setString(2, table); }, rs -> (UUID) rs.getObject(1));
  }

  private boolean covered(String entityType, UUID run) {
    return Jdbc.queryOne(adminDs, "covered",
        "SELECT 1 FROM core.resolution_run_coverage WHERE tenant_id = ? AND entity_type = ? AND run_id = ?",
        ps -> { ps.setLong(1, TENANT); ps.setString(2, entityType); ps.setObject(3, run); },
        rs -> true).isPresent();
  }

  private List<String> resolvedIds(String entityType, String source) {
    return Jdbc.query(adminDs, "resolvedIds",
        "SELECT source_id FROM core.entity_identity WHERE tenant_id = ? AND entity_type = ? AND source = ?"
            + " AND state = 'resolved'",
        ps -> { ps.setLong(1, TENANT); ps.setString(2, entityType); ps.setString(3, source); },
        rs -> rs.getString(1));
  }
}
