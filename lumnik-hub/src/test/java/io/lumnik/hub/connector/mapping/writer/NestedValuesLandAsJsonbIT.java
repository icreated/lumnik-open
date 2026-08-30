package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A nested value written to a dynamic table lands as real, queryable {@code jsonb}.
 *
 * <p>It used to land nowhere. {@code split} with {@code into-array: true} — documented in
 * {@code docs/connectors/transformers.md} as "replace the cell as an array" — puts a {@code List}
 * in the row; the inferrer typed the column {@code JSONB}; {@code toJdbc} serialised the list to a
 * String; and pgJDBC sent that String as {@code varchar}, which Postgres refuses:
 *
 * <pre>column "tags" is of type jsonb but expression is of type character varying</pre>
 *
 * <p>A {@code Map} failed differently and worse — pgJDBC maps {@code java.util.Map} to hstore, so
 * the bind died with "No hstore extension installed" before Postgres saw anything. Either way the
 * throw reached {@code ChunkExecutor}, became a {@code ChunkResult.Failure}, and left the cursor
 * where it was: a documented hook, on a documented connector, turning a run into a poison pill.
 *
 * <p>The fix is the idiom {@code HybridJsonbWriter} already uses — bind the serialised value with
 * {@code Types.OTHER} so Postgres infers the column's type — which needs nothing from the INSERT
 * builder, so it stays type-blind and positional.
 */
@QuarkusTest
class NestedValuesLandAsJsonbIT {

  @Inject DynamicTableWriter writer;
  @Inject EntityManager em;
  @Inject UserTransaction utx;

  private String writeRow(String prefix, Map<String, Object> row) {
    String mappingId = prefix + System.nanoTime();
    writeInto(mappingId, row);
    return mappingId;
  }

  private void writeInto(String mappingId, Map<String, Object> row) {
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());
    Map<String, Object> sys = Map.of(
        "_row_hash", "h_" + mappingId + "_" + System.nanoTime(), "_tenant", 1L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

    TenantContext.runAsSystem(() -> {
      try {
        utx.begin();
        writer.write(spec, List.of(row), List.of(sys), 1L);
        utx.commit();
      } catch (Exception e) {
        rollbackQuietly(e);
        throw new RuntimeException(e);
      }
    });
  }

  /**
   * A failed write must not leave the transaction open: the next test would call
   * {@code begin()} inside it and die of a nested transaction, hiding the real failure behind a
   * second one. A rollback that itself fails rides along as suppressed rather than replacing the
   * cause.
   */
  private void rollbackQuietly(Exception cause) {
    try {
      if (utx.getStatus() != Status.STATUS_NO_TRANSACTION) {
        utx.rollback();
      }
    } catch (Exception e) {
      cause.addSuppressed(e);
    }
  }

  private String columnType(String table, String column) {
    return (String) em.createNativeQuery(
            "SELECT data_type FROM information_schema.columns "
            + "WHERE table_schema = 'tenant_1_data' AND table_name = ?1 AND column_name = ?2")
        .setParameter(1, table).setParameter(2, column)
        .getSingleResult();
  }

  @Test
  void aListLandsAsJsonbAndIsQueryableAsOne() {
    // Exactly what `split` with into-array: true puts in the row.
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tags", List.of("A", "B"));
    String table = writeRow("test_list_", row);

    assertThat(columnType(table, "tags")).isEqualTo("jsonb");

    Number matches = (Number) em.createNativeQuery(
            "SELECT count(*) FROM tenant_1_data." + table + " WHERE tags @> '[\"A\"]'::jsonb")
        .getSingleResult();
    assertThat(matches.intValue())
        .as("stored as a json array, not as text that merely looks like one")
        .isEqualTo(1);
  }

  @Test
  void aMapLandsAsJsonbToo_notAsHstoreAndNotAsText() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("street", "12 rue de la Paix");
    nested.put("city", "Lyon");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("address", nested);
    String table = writeRow("test_map_", row);

    assertThat(columnType(table, "address")).isEqualTo("jsonb");

    String city = (String) em.createNativeQuery(
            "SELECT address ->> 'city' FROM tenant_1_data." + table)
        .getSingleResult();
    assertThat(city).as("addressable by key, which is the whole point of jsonb").isEqualTo("Lyon");
  }

  @Test
  void aPlainColumnBesideANestedOneIsUntouched() {
    // The nested binding must not change how ordinary values are sent.
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("label", "Chair");
    row.put("tags", List.of("A"));
    String table = writeRow("test_mixed_", row);

    assertThat(columnType(table, "label")).isEqualTo("text");
    assertThat(columnType(table, "tags")).isEqualTo("jsonb");
    assertThat((String) em.createNativeQuery(
            "SELECT label FROM tenant_1_data." + table).getSingleResult())
        .isEqualTo("Chair");
  }

  @Test
  void aFailedWriteLeavesNoOpenTransaction() {
    // The rollback must ACT, not merely be written. A row naming a system column is the house's
    // own deterministic refusal, so this drives the catch block for real: without the rollback the
    // transaction stays active and the assertion below sees STATUS_ACTIVE.
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("_row_hash", "stolen");

    assertThatThrownBy(() -> writeRow("test_rollback_", row))
        .as("the writer refuses a row that claims a system column")
        .isInstanceOf(RuntimeException.class);

    assertThat(txStatus())
        .as("a failed write hands the thread back without an open transaction")
        .isEqualTo(Status.STATUS_NO_TRANSACTION);
  }


  @Test
  void aValueJacksonCannotSerializeFailsAsItselfNotAsBrokenJson() {
    // toJson used to fall back to v.toString(), which for a List is "[java.lang.Object@1a2b]" —
    // not JSON. Bound to a jsonb column that reaches Postgres as "invalid input syntax for type
    // json": the same poison pill this PR removes, wearing a cause that points at the database
    // instead of at the value. The serialization failure must surface as itself.
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("payload", List.of(new Object()));

    assertThatThrownBy(() -> writeRow("test_unserializable_", row))
        .as("the failure names serialization, not Postgres' json parser")
        .hasStackTraceContaining("JSON serialization failed");

    assertThat(txStatus())
        .as("and it still hands the thread back clean")
        .isEqualTo(Status.STATUS_NO_TRANSACTION);
  }


  @Test
  void aSparseFirstChunkFreezesTheColumnToText() {
    // What the docs claim without qualification: into-array gives you a jsonb column. It does —
    // unless the first chunk saw only NULLs for that column. inferColumn then has no observed
    // value and no source hint, so it picks TEXT, and evolution only ever ADD COLUMN: no widening
    // exists, so the column stays TEXT for the table's life. This asserts what actually happens to
    // the arrays that arrive afterwards.
    String mappingId = "test_sparse_" + System.nanoTime();
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("tags", null);
    first.put("label", "seen");
    writeInto(mappingId, first);

    assertThat(columnType(mappingId, "tags"))
        .as("a null-only first chunk types the column from nothing")
        .isEqualTo("text");

    Map<String, Object> second = new LinkedHashMap<>();
    second.put("tags", List.of("A", "B"));
    second.put("label", "later");
    writeInto(mappingId, second);

    assertThat(columnType(mappingId, "tags"))
        .as("no widening exists — the column is TEXT for good")
        .isEqualTo("text");

    String stored = (String) em.createNativeQuery(
            "SELECT tags FROM tenant_1_data." + mappingId + " WHERE label = 'later'")
        .getSingleResult();
    assertThat(stored)
        .as("the array is not lost, but it is json TEXT — @> and a GIN index do not apply")
        .isEqualTo("[\"A\",\"B\"]");
  }

  private int txStatus() {
    try {
      return utx.getStatus();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
