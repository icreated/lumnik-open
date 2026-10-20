package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class EvolutionIT {

  @Inject DynamicTableWriter writer;
  @Inject EntityManager em;
  @Inject UserTransaction utx;

  /** One chunk, one row, committed — the production shape the writer expects. */
  private void writeChunk(MappingTarget.Table spec, String hash, Map<String, Object> row) {
    Map<String, Object> sys = Map.of(
        "_row_hash", hash, "_tenant", 1L, "_mapping", spec.name(),
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    TenantContext.runAsSystem(() -> {
      try { utx.begin(); writer.write(spec, List.of(row), List.of(sys), 1L); utx.commit(); }
      catch (Exception e) { throw new RuntimeException(e); }
    });
  }

  private static MappingTarget.Table spec(String mappingId) {
    return new MappingTarget.Table(
        "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());
  }

  @Test
  void alters_table_when_new_column_appears() {
    String mappingId = "test_evolve_" + System.nanoTime();
    MappingTarget.Table spec = spec(mappingId);

    writeChunk(spec, "h1_" + mappingId, Map.of("email", "a@x.com"));
    writeChunk(spec, "h2_" + mappingId, Map.of("email", "b@x.com", "phone", "0123456789"));

    String phone = (String) em.createNativeQuery(
            "SELECT phone FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?1")
        .setParameter(1, "h2_" + mappingId)
        .getSingleResult();
    assertThat(phone).isEqualTo("0123456789");
  }

  @Test
  void a_source_column_named_seen_run_is_kept_on_a_table_that_does_not_refresh() {
    // #1214 review: _seen_run is a hub column only where the writer marksSeen (#1195); anywhere
    // else it is the source's, and the drift read must not take it for the hub's and drop its values
    String mappingId = "test_seen_payload_" + System.nanoTime();
    MappingTarget.Table spec = spec(mappingId);

    writeChunk(spec, "h1_" + mappingId, Map.of("email", "a@x.com", "_seen_run", "kept"));

    Object kept = em.createNativeQuery(
            "SELECT _seen_run FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?1")
        .setParameter(1, "h1_" + mappingId)
        .getSingleResult();
    assertThat(kept).isEqualTo("kept");
  }

  @Test
  void a_column_added_mid_run_is_typed_from_the_chunk_that_introduces_it() {
    // #485: type inference ran only in createSchema, so evolveSchema added every later column as
    // TEXT. A number or a boolean a producer introduces on day two therefore froze to its string
    // form — silently, with no warning and no report — and the joins and comparisons the first
    // chunk's columns support were not available on the second's. Schema drift is kafka's daily
    // bread, and a jdbc source gains columns the same way.
    String mappingId = "test_evolve_typed_" + System.nanoTime();
    MappingTarget.Table spec = spec(mappingId);

    writeChunk(spec, "t1_" + mappingId, Map.of("email", "a@x.com"));

    Map<String, Object> later = new HashMap<>();
    later.put("email", "b@x.com");
    later.put("amount", new BigDecimal("42.5"));
    later.put("paid", Boolean.TRUE);
    // java.sql.Timestamp: what a live jdbc reader actually yields for a TIMESTAMPTZ column.
    // Instant/ZonedDateTime are exercised directly in DynamicTableWriterIT (#506).
    later.put("seen", Timestamp.valueOf("2026-09-13 10:15:30"));
    later.put("label", "still text");
    writeChunk(spec, "t2_" + mappingId, later);

    assertThat(columnTypes(mappingId)).containsAllEntriesOf(Map.of(
        "amount", "numeric",
        "paid", "boolean",
        "seen", "timestamp with time zone",
        "label", "text"));

    // And the value is READ BACK as a number, not as a string that looks like one.
    Object amount = em.createNativeQuery(
            "SELECT amount FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?1")
        .setParameter(1, "t2_" + mappingId)
        .getSingleResult();
    assertThat(amount).isInstanceOf(BigDecimal.class);
    assertThat(((BigDecimal) amount)).isEqualByComparingTo("42.5");
  }

  /**
   * A whole-number column is created {@code BIGINT} (#497), and the chunk that first carries a
   * fractional value for it WIDENS it rather than dying on it.
   *
   * <p>This is what makes the narrowing safe. Types are decided from the chunk in hand — the
   * inferrer never sees a source's full range — so a {@code credit_limit} that reads
   * {@code 1000, 2000} in the first chunk and {@code 748.5} in the fifth would otherwise fail the
   * INSERT ("column is of type bigint but expression is of type double precision") and take the
   * run with it. One direction only, and only this pair: bigint → numeric loses nothing.
   */
  @Test
  void a_whole_number_column_is_bigint_and_is_widened_when_a_fraction_arrives() {
    String mappingId = "test_widen_" + System.nanoTime();
    MappingTarget.Table spec = spec(mappingId);

    writeChunk(spec, "w1_" + mappingId, Map.of("credit_limit", 1000));
    assertThat(columnTypes(mappingId)).containsEntry("credit_limit", "bigint");

    writeChunk(spec, "w2_" + mappingId, Map.of("credit_limit", 748.5d));
    assertThat(columnTypes(mappingId)).containsEntry("credit_limit", "numeric");

    Object limit = em.createNativeQuery(
            "SELECT credit_limit FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?1")
        .setParameter(1, "w2_" + mappingId)
        .getSingleResult();
    assertThat((BigDecimal) limit).isEqualByComparingTo("748.5");

    // The row written BEFORE the widening is still there, and still says 1000.
    Object earlier = em.createNativeQuery(
            "SELECT credit_limit FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?1")
        .setParameter(1, "w1_" + mappingId)
        .getSingleResult();
    assertThat((BigDecimal) earlier).isEqualByComparingTo("1000");
  }

  /**
   * The detection itself, without a datasource: only a registered {@code BIGINT} fed a fractional
   * number widens. A text column holding "1.5" does not (it is not a {@code Number}), and a column
   * already {@code NUMERIC(18,4)} has nowhere to go.
   */
  @Test
  void widenings_namesOnlyTheBigintColumnsAFractionMakesTooNarrow() {
    Map<String, String> registered = Map.of(
        "credit_limit", "BIGINT", "qty", "BIGINT", "price", "NUMERIC(18,4)", "code", "TEXT");
    List<Map<String, Object>> rows = List.of(
        Map.of("credit_limit", 1000, "qty", 3, "price", 2.5d, "code", "1.5"),
        Map.of("credit_limit", 748.5d, "qty", 4, "price", 9.0d, "code", "2.5"));

    assertThat(DynamicTableWriter.widenings(rows, registered)).containsExactly("credit_limit");
  }

  /**
   * Columns first seen in the SAME chunk are added in the order that chunk presents them.
   *
   * <p>#496 made the created table follow the source's field order, and stopped there: the ADD
   * COLUMN loop iterated a {@code HashSet}, and Postgres's physical column order follows the order
   * columns are added. So a producer adding three fields on day two got them in hash order — the
   * very scramble #496 was about, on the path schema drift actually takes.
   */
  @Test
  void columns_added_in_one_chunk_land_in_the_order_that_chunk_presents_them() {
    String mappingId = "test_evolve_order_" + System.nanoTime();
    MappingTarget.Table spec = spec(mappingId);

    writeChunk(spec, "o1_" + mappingId, Map.of("code", "C1"));

    // These three scramble under HashSet iteration (vat_number, loyalty_tier, segment) — chosen so
    // the test can tell insertion order from hash order rather than passing by luck.
    Map<String, Object> later = new LinkedHashMap<>();
    later.put("code", "C2");
    later.put("vat_number", "FR1");
    later.put("segment", "gold");
    later.put("loyalty_tier", "3");
    writeChunk(spec, "o2_" + mappingId, later);

    assertThat(payloadColumnsInOrder(mappingId))
        .containsExactly("code", "vat_number", "segment", "loyalty_tier");
  }

  /** The table's payload columns as Postgres holds them — its own physical order. */
  private List<String> payloadColumnsInOrder(String table) {
    @SuppressWarnings("unchecked")
    List<String> names = em.createNativeQuery(
            "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema = 'tenant_1_data' AND table_name = ?1 ORDER BY ordinal_position")
        .setParameter(1, table).getResultList();
    return names.stream().filter(n -> !DynamicTableWriter.SYSTEM_COLUMNS.contains(n)).toList();
  }

  private Map<String, String> columnTypes(String table) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT column_name, data_type FROM information_schema.columns "
            + "WHERE table_schema = 'tenant_1_data' AND table_name = ?1").
        setParameter(1, table).getResultList();
    Map<String, String> out = new HashMap<>();
    for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
    return out;
  }
}
