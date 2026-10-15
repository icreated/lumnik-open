package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.event.RunTableStats;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.purgeWebhookEndpoints;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.scalar;
import static io.lumnik.hub.testsupport.EventItSupport.seedWebhookEndpoint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #904: the incremental resolution pass reads only the sources whose table the run's {@code
 * ConnectorRunCompleted} names, and those names are what {@link RunTableStats} recorded. So every
 * insert path of the one writer that stamps {@code _run_id} must record the table it wrote — a path
 * that did not would leave that run's rows unresolved, silently.
 *
 * <p>Both paths (un-gated batch, gated multi-row INSERT … RETURNING) × every {@code on_conflict}
 * mode, the mode list read from the enum itself so a new mode is covered the day it exists. The gate
 * is switched on for a tenant of its own, so no other test's tenant sees it.
 */
@QuarkusTest
class DynamicTableWriterRunStatsIT {

  private static final long TENANT = 99_043;
  private static final String SUBSCRIPTION = "dtw904-sub";

  @Inject DynamicTableWriter writer;
  @Inject RunTableStats stats;
  @Inject UserTransaction utx;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @AfterEach
  void cleanup() {
    purgeWebhookEndpoints(adminDs, SUBSCRIPTION);
    execSql(adminDs, "DELETE FROM core.event_outbox WHERE tenant_id = " + TENANT);
    for (String table : Jdbc.query(adminDs, "dtw904 tables",
        "SELECT table_name FROM connector.dynamic_table WHERE tenant_id = ?",
        ps -> ps.setLong(1, TENANT), rs -> rs.getString(1))) {
      execSql(adminDs, "DROP TABLE IF EXISTS connector." + table);
    }
    execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE tenant_id = " + TENANT);
  }

  static Stream<Arguments> everyInsertPath() {
    return Arrays.stream(MappingTarget.OnConflict.values())
        .flatMap(mode -> Stream.of(Arguments.of(mode, false), Arguments.of(mode, true)));
  }

  @ParameterizedTest(name = "{0}, gated={1}")
  @MethodSource("everyInsertPath")
  void every_insert_path_records_the_table_its_run_wrote(MappingTarget.OnConflict mode, boolean gated) {
    if (gated) seedWebhookEndpoint(adminDs, utx, TENANT, SUBSCRIPTION, "data.row.changed");
    String mappingId = "dtw904_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table("connector", "t_" + mappingId, true, mode, List.of());
    UUID run = UUID.randomUUID();
    Map<String, Object> sys = Map.of(
        "_row_hash", "h_" + mappingId, "_tenant", TENANT, "_mapping", mappingId,
        "_run_id", run, "_mapping_version", 1, "_source", "test");

    runInTxAsSystem(utx, () -> writer.write(spec, List.of(Map.of("email", "a@x.com")), List.of(sys), TENANT));

    assertThat(scalar(adminDs, "SELECT count(*) FROM core.event_outbox WHERE tenant_id = " + TENANT
        + " AND event_type = 'data.row.changed' AND entity = 'connector.t_" + mappingId + "'"))
        .as("the path under test actually ran: the gated one stages a row event, the other none")
        .isEqualTo(gated ? 1L : 0L);
    assertThat(stats.drain(run)).containsOnlyKeys("connector.t_" + mappingId);
  }
}
