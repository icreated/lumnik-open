package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class DynamicTableWriterIT {

  @Inject DynamicTableWriter writer;
  @Inject EntityManager em;
  @Inject UserTransaction utx;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  // Registry case: dynamic tables used to get the UNGUARDED cast — an empty GUC threw 22P02
  // on the very tables V21's motivating incident crashed on. Every freshly created policy
  // must carry the NULLIF guard (empty GUC → zero rows, not an error).
  @Test
  void createdPolicy_carriesTheV21NullifGuard() throws Exception {
    String mappingId = "test_guard_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());
    Map<String, Object> sys = Map.of(
        "_row_hash", "hg_" + mappingId, "_tenant", 1L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(Map.of("email", "g@x.com")), List.of(sys), 1L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    String qual = (String) em.createNativeQuery(
            "SELECT qual FROM pg_policies WHERE policyname = 'tenant_isolation' "
            + "AND schemaname = 'tenant_1_data' AND tablename = ?1")
        .setParameter(1, mappingId)
        .getSingleResult();
    assertThat(qual).as("the V21 guard must be born with the table").contains("NULLIF");
  }

  @Test
  void creates_table_on_first_write_and_inserts() throws Exception {
    String mappingId = "test_create_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data",
        mappingId,
        true,
        MappingTarget.OnConflict.SKIP,
        List.of()
    );
    Map<String, Object> row = Map.of("email", "a@x.com", "name", "Alice");
    Map<String, Object> sys = Map.of(
        "_row_hash", "h1_" + mappingId,
        "_tenant", 1L,
        "_mapping", mappingId,
        "_run_id", UUID.randomUUID(),
        "_mapping_version", 1,
        "_source", "test"
    );

    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(row), List.of(sys), 1L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    long count = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?1")
        .setParameter(1, "h1_" + mappingId)
        .getSingleResult()).longValue();
    assertThat(count).isEqualTo(1);
  }

  @Test
  void on_conflict_skip_does_not_duplicate() throws Exception {
    String mappingId = "test_skip_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());
    String hash = "h1_" + mappingId;
    Map<String, Object> row = Map.of("email", "a@x.com");
    Map<String, Object> sys = Map.of(
        "_row_hash", hash, "_tenant", 1L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

    // Insert twice
    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(row), List.of(sys), 1L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });
    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(row), List.of(sys), 1L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    long count = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?1")
        .setParameter(1, hash).getSingleResult()).longValue();
    assertThat(count).isEqualTo(1);
  }

  // #506: SchemaInferrer types an Instant/ZonedDateTime column TIMESTAMPTZ, but bindValue's bare
  // ps.setObject(idx, v) leaves pgJDBC unable to infer a SQL type for either — the insert dies
  // even on the FIRST chunk. No shipped reader produces these today (jdbc yields
  // java.sql.Timestamp, csv/rest yield String), so this is exercised directly rather than through
  // a connector, exactly as the inferrer's own values-not-strings contract is.
  @Test
  void writes_an_instant_valued_column_the_inferrer_typed_timestamptz() throws Exception {
    String mappingId = "test_instant_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());
    Instant at = Instant.parse("2026-05-23T14:30:00Z");
    Map<String, Object> row = Map.of("occurred_at", at);
    Map<String, Object> sys = Map.of(
        "_row_hash", "h_instant_" + mappingId, "_tenant", 1L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(row), List.of(sys), 1L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    OffsetDateTime stored = readOccurredAt(mappingId, "h_instant_" + mappingId);
    assertThat(stored).isEqualTo(at.atOffset(ZoneOffset.UTC));
  }

  @Test
  void writes_a_zoneddatetime_valued_column_the_inferrer_typed_timestamptz() throws Exception {
    String mappingId = "test_zdt_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());
    ZonedDateTime at = ZonedDateTime.of(2026, 5, 23, 14, 30, 0, 0, ZoneOffset.UTC);
    Map<String, Object> row = Map.of("occurred_at", at);
    Map<String, Object> sys = Map.of(
        "_row_hash", "h_zdt_" + mappingId, "_tenant", 1L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(row), List.of(sys), 1L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    OffsetDateTime stored = readOccurredAt(mappingId, "h_zdt_" + mappingId);
    assertThat(stored).isEqualTo(at.toOffsetDateTime());
  }

  /**
   * Read on the admin datasource, which bypasses RLS — {@code DynamicTableWriter} itself writes
   * dynamic tables there, so this reads back exactly what landed rather than routing through the
   * RLS-enforced default datasource {@code em} uses for the platform's own entities.
   */
  private OffsetDateTime readOccurredAt(String mappingId, String rowHash) {
    return Jdbc.queryOne(adminDs, "read the inserted occurred_at",
            "SELECT occurred_at FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?",
            ps -> ps.setString(1, rowHash),
            rs -> rs.getObject(1, OffsetDateTime.class))
        .orElseThrow();
  }

  @Test
  void rls_isolates_tenants() throws Exception {
    String mappingId = "test_rls_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());

    Map<String, Object> row1 = Map.of("name", "Alice");
    Map<String, Object> sys1 = Map.of("_row_hash", "h_t1", "_tenant", 1L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "t1");
    Map<String, Object> row2 = Map.of("name", "Bob");
    Map<String, Object> sys2 = Map.of("_row_hash", "h_t2", "_tenant", 2L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "t2");

    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(row1), List.of(sys1), 1L);
        writer.write(spec, List.of(row2), List.of(sys2), 2L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    // Total rows = 2
    long total = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM tenant_data." + mappingId)
        .getSingleResult()).longValue();
    assertThat(total).isEqualTo(2);
  }

  /**
   * #525 — {@code createSchema}'s own {@code CREATE TABLE IF NOT EXISTS} no-ops when a sibling
   * mapping already created this physical table, so the writer used to register whatever the
   * new mapping's OWN first chunk inferred over it regardless of what the table already is. A
   * second mapping onto an established BIGINT column, fed a fractional value on its very first
   * row, inferred NUMERIC and registered it — while the column stayed physically bigint — and
   * the insert that should have widened it died instead ("column is of type bigint but
   * expression is of type double precision"). The registry must reconcile against the PHYSICAL
   * table instead, widening the real column, exactly as evolveSchema would for a chunk arriving
   * later under the same mapping.
   */
  @Test
  void secondMappingOntoAnExistingTableWidensThePhysicalColumnInsteadOfMisregisteringIt() throws Exception {
    String table = "test_drift525_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", table, true, MappingTarget.OnConflict.SKIP, List.of());

    String mappingA = table + "_a";
    Map<String, Object> sysA = Map.of(
        "_row_hash", "hA_" + table, "_tenant", 1L, "_mapping", mappingA,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(Map.of("qty", 3L)), List.of(sysA), 1L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    String mappingB = table + "_b";
    Map<String, Object> sysB = Map.of(
        "_row_hash", "hB_" + table, "_tenant", 1L, "_mapping", mappingB,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(Map.of("qty", 5.5)), List.of(sysB), 1L);
        utx.commit();
      } catch (Exception e) { throw new RuntimeException(e); }
    });

    double qty = ((Number) em.createNativeQuery(
            "SELECT qty FROM tenant_1_data." + table + " WHERE _row_hash = ?1")
        .setParameter(1, "hB_" + table)
        .getSingleResult()).doubleValue();
    assertThat(qty).isEqualTo(5.5);
  }

  /**
   * #525 review — the sequential fix above still had a TOCTOU window: {@code createSchema} read
   * {@code DriftDetector.actualSchema} BEFORE {@code CREATE TABLE IF NOT EXISTS}, so two mappings
   * doing their first write to the same brand-new table concurrently could both observe "does not
   * exist", both infer independently, and the loser's no-op create would still register ITS OWN
   * inference — the very registry/physical mismatch #525 set out to end. Connector runs fan out
   * onto virtual threads, so this is not theoretical.
   *
   * <p>The fix must not depend on winning a timing race to be correct: reading the physical schema
   * has to happen strictly AFTER the (idempotent) create, whichever mapping's statement wins it.
   * This pins that property directly rather than the race itself — it must pass on every
   * interleaving, not just the ones this test happens to trigger.
   */
  @Test
  void concurrentFirstWritesFromTwoMappingsOntoTheSameNewTableNeverMisregisterTheSchema() throws Exception {
    String table = "test_race525_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", table, true, MappingTarget.OnConflict.SKIP, List.of());

    String mappingA = table + "_a";
    Map<String, Object> sysA = Map.of(
        "_row_hash", "hA_" + table, "_tenant", 1L, "_mapping", mappingA,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    String mappingB = table + "_b";
    Map<String, Object> sysB = Map.of(
        "_row_hash", "hB_" + table, "_tenant", 1L, "_mapping", mappingB,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);

    ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
      Thread t = new Thread(r, "drift525-race-it");
      t.setDaemon(true);
      return t;
    });
    try {
      Future<Exception> fa = pool.submit(
          () -> writeRacing(ready, go, spec, sysA, Map.of("qty", 3L)));
      Future<Exception> fb = pool.submit(
          () -> writeRacing(ready, go, spec, sysB, Map.of("qty", 5.5)));

      if (!ready.await(20, TimeUnit.SECONDS)) throw new AssertionError("racers never got ready");
      go.countDown();

      Exception exA = fa.get(20, TimeUnit.SECONDS);
      Exception exB = fb.get(20, TimeUnit.SECONDS);
      assertThat(exA).as("mapping A's write").isNull();
      assertThat(exB).as("mapping B's write").isNull();
    } finally {
      pool.shutdownNow();
    }

    double qtyB = ((Number) em.createNativeQuery(
            "SELECT qty FROM tenant_1_data." + table + " WHERE _row_hash = ?1")
        .setParameter(1, "hB_" + table)
        .getSingleResult()).doubleValue();
    assertThat(qtyB).isEqualTo(5.5);
  }

  /**
   * #525 review round 2 — {@code createSchema}'s own {@code createIndexes()} can raise the very
   * same SQLSTATE ({@code 23505}) a lost creation race did before {@link
   * #concurrentFirstWritesFromTwoMappingsOntoTheSameNewTableNeverMisregisterTheSchema}'s fix, but
   * for a completely different, genuine reason: real duplicate data violates a UNIQUE index this
   * mapping declares. That failure must still fail ingestion — silently accepting an unindexed
   * table would be worse than the race #525 set out to close, and is exactly why the fix landed
   * as a lock (mutual exclusion) rather than a caught SQLSTATE (a guess that cannot tell "lost the
   * creation race" apart from "a real constraint was violated").
   */
  @Test
  void aDeclaredUniqueIndexOverExistingDuplicateDataStillFailsIngestion() throws Exception {
    String table = "test_dupidx525_" + System.nanoTime();
    MappingTarget.Table specNoIndex = new MappingTarget.Table(
        "tenant_1_data", table, true, MappingTarget.OnConflict.SKIP, List.of());

    String mappingA = table + "_a";
    Map<String, Object> sysA1 = Map.of(
        "_row_hash", "hA1_" + table, "_tenant", 1L, "_mapping", mappingA,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    Map<String, Object> sysA2 = Map.of(
        "_row_hash", "hA2_" + table, "_tenant", 1L, "_mapping", mappingA,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(() -> writer.write(
        specNoIndex, List.of(Map.of("code", "DUP"), Map.of("code", "DUP")),
        List.of(sysA1, sysA2), 1L)));

    MappingTarget.Table specWithUniqueIndex = new MappingTarget.Table(
        "tenant_1_data", table, true, MappingTarget.OnConflict.SKIP,
        List.of(new MappingTarget.Index(List.of("code"), true)));
    String mappingB = table + "_b";
    Map<String, Object> sysB = Map.of(
        "_row_hash", "hB_" + table, "_tenant", 1L, "_mapping", mappingB,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

    assertThatThrownBy(() -> TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(
        () -> writer.write(specWithUniqueIndex, List.of(Map.of("code", "OK")), List.of(sysB), 1L))))
        .as("a real duplicate-data constraint violation must not be swallowed as a lost creation race")
        .hasStackTraceContaining("duplicate");
  }

  /** Waits for both racers to be ready, then fires {@code writer.write} on the release signal. */
  private Exception writeRacing(CountDownLatch ready, CountDownLatch go, MappingTarget.Table spec,
                                Map<String, Object> sys, Map<String, Object> row) {
    try {
      ready.countDown();
      if (!go.await(20, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting to start");
      TenantContext.runAsSystem(() -> QuarkusTransaction.requiringNew().run(
          () -> writer.write(spec, List.of(row), List.of(sys), 1L)));
      return null;
    } catch (Exception e) {
      return e;
    }
  }
}
