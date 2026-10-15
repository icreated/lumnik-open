package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** #989, on scratch tables with autovacuum off; {@code pg_class.reltuples} is the witness (-1 until the first ANALYZE). */
@QuarkusTest
class CrosswalkStatisticsIT {

  private static final String SCHEMA = "connector";
  private static final String COLD = "t_stats989_cold";
  private static final String SMALL = "t_stats989_small";
  private static final String WARM = "t_stats989_warm";
  private static final String LOCKED = "t_stats989_locked";
  private static final String VACUUMED = "t_stats989_vacuumed";
  private static final String JUST_ANALYSED = "t_stats989_just_analysed";
  private static final List<String> TABLES = List.of(COLD, SMALL, WARM, LOCKED, VACUUMED, JUST_ANALYSED);

  @Inject CrosswalkStatistics statistics;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @BeforeEach
  void setUp() throws Exception {
    for (String t : TABLES) {
      exec("DROP TABLE IF EXISTS " + SCHEMA + "." + t);
      exec("CREATE TABLE " + SCHEMA + "." + t + " (id bigint, v text) WITH (autovacuum_enabled = false)");
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    for (String t : TABLES) exec("DROP TABLE IF EXISTS " + SCHEMA + "." + t);
  }

  @Test
  void aTableWithRowsThatWasNeverAnalysedGetsAnalysed() throws Exception {
    fill(COLD, 1500);
    assertThat(reltuples(COLD)).as("never analysed").isEqualTo(-1);

    statistics.analyseNeverAnalysed(SCHEMA, List.of(COLD));

    assertThat(reltuples(COLD)).isEqualTo(1500);
  }

  @Test
  void aTableTooSmallToMisleadThePlannerIsLeftAlone() throws Exception {
    fill(SMALL, CrosswalkStatistics.MIN_ROWS - 1);

    statistics.analyseNeverAnalysed(SCHEMA, List.of(SMALL));

    assertThat(reltuples(SMALL)).isEqualTo(-1);
  }

  @Test
  void aTableAnalysedOnceIsNotAnalysedAgain() throws Exception {
    fill(WARM, 1500);
    exec("ANALYZE " + SCHEMA + "." + WARM);
    awaitAnalysedInStatistics(WARM);
    fill(WARM, 1500);

    statistics.analyseNeverAnalysed(SCHEMA, List.of(WARM));

    assertThat(reltuples(WARM)).as("still the first ANALYZE's count").isEqualTo(1500);
  }

  @Test
  void aTableAnotherCallerIsAlreadyAnalysingIsLeftToThatCaller() throws Exception {
    fill(LOCKED, 1500);
    try (Connection other = adminDs.getConnection(); Statement st = other.createStatement()) {
      st.execute("SELECT pg_advisory_lock(hashtextextended('" + SCHEMA + "." + LOCKED + "', 0))");

      statistics.analyseNeverAnalysed(SCHEMA, List.of(LOCKED));

      assertThat(reltuples(LOCKED)).as("not analysed while the other caller holds the lock").isEqualTo(-1);
      st.execute("SELECT pg_advisory_unlock(hashtextextended('" + SCHEMA + "." + LOCKED + "', 0))");
    }

    statistics.analyseNeverAnalysed(SCHEMA, List.of(LOCKED));

    assertThat(reltuples(LOCKED)).as("asked again once the lock is free").isEqualTo(1500);
  }

  @Test
  void aVacuumedTableThatWasNeverAnalysedStillGetsAnalysed() throws Exception {
    // VACUUM records reltuples but collects no column statistics, which is what index choice needs.
    fill(VACUUMED, 1500);
    exec("VACUUM " + SCHEMA + "." + VACUUMED);
    fill(VACUUMED, 1500);
    assertThat(reltuples(VACUUMED)).isEqualTo(1500);

    statistics.analyseNeverAnalysed(SCHEMA, List.of(VACUUMED));

    assertThat(reltuples(VACUUMED)).isEqualTo(3000);
  }

  @Test
  void aTableAnalysedMeanwhileIsNotAnalysedAgainEvenBeforeTheStatisticsViewShowsIt() throws Exception {
    fill(JUST_ANALYSED, 1500);
    exec("ANALYZE " + SCHEMA + "." + JUST_ANALYSED);   // no wait: pg_stat_user_tables may still say "never"
    fill(JUST_ANALYSED, 1500);

    statistics.analyseNeverAnalysed(SCHEMA, List.of(JUST_ANALYSED));

    assertThat(reltuples(JUST_ANALYSED)).as("the first ANALYZE's count").isEqualTo(1500);
  }

  @Test
  void theTablesTheCrosswalkAnalysisTargetsExist() throws Exception {
    for (String table : CrosswalkStatistics.CROSSWALK) {
      try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("SELECT 1 FROM pg_stat_user_tables WHERE schemaname = '"
               + CrosswalkStatistics.SCHEMA + "' AND relname = '" + table + "'")) {
        assertThat(rs.next()).as(table).isTrue();
      }
    }
  }

  @Test
  void aMissingTableIsASkipNotAFailure() {
    assertThatCode(() -> statistics.analyseNeverAnalysed(SCHEMA, List.of("t_stats989_gone")))
        .doesNotThrowAnyException();
  }

  private void fill(String table, int rows) throws Exception {
    exec("INSERT INTO " + SCHEMA + "." + table + " SELECT g, 'v' || g FROM generate_series(1, " + rows + ") g");
  }

  private long reltuples(String table) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT reltuples::bigint FROM pg_class WHERE oid = '"
             + SCHEMA + "." + table + "'::regclass")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** The statistics view lags the ANALYZE by a flush; the guard under test reads it. */
  private void awaitAnalysedInStatistics(String table) throws Exception {
    for (int i = 0; i < 100; i++) {
      try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("SELECT last_analyze IS NOT NULL FROM pg_stat_user_tables "
               + "WHERE schemaname = '" + SCHEMA + "' AND relname = '" + table + "'")) {
        if (rs.next() && rs.getBoolean(1)) return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("pg_stat_user_tables never showed the ANALYZE of " + table);
  }

  private void exec(String sql) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }
}
