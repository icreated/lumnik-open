package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1338: a CSV value read with {@code parse-number: { integer: true }} creates a {@code bigint}
 * column — not the {@code numeric(18,4)} a plain parse-number makes, where an id reads
 * {@code 1024944.0000}. A table the hub already created keeps its type: turning the option on later
 * writes the same number into the existing {@code text} column, as docs/connectors/transformers.md says.
 */
@QuarkusTest
class ParseNumberIntegerLandsAsBigintIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject DynamicTableWriter writer;
  @Inject HookCatalog catalog;
  @Inject EntityManager em;
  @Inject UserTransaction utx;

  private Map<String, Object> parsed(String raw, String params) throws Exception {
    JsonNode p = JSON.readTree(params);
    var hook = catalog.cell("parse-number");
    hook.validateParams(p);
    Map<String, Object> row = new HashMap<>();
    row.put("product_id", raw);
    hook.apply(row, p, new MappingState());
    return row;
  }

  private void write(String table, Map<String, Object> row) {
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", table, true, MappingTarget.OnConflict.SKIP, List.of());
    Map<String, Object> sys = Map.of(
        "_row_hash", "h_" + table + "_" + System.nanoTime(), "_tenant", 1L, "_mapping", table,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");
    TenantContext.runAsSystem(() -> {   // a failed write's open transaction is DanglingTransactionNet's to roll back
      try {
        utx.begin();
        writer.write(spec, List.of(row), List.of(sys), 1L);
        utx.commit();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  private String columnType(String table) {
    return (String) em.createNativeQuery("SELECT data_type FROM information_schema.columns "
            + "WHERE table_schema = 'tenant_1_data' AND table_name = ?1 AND column_name = 'product_id'")
        .setParameter(1, table).getSingleResult();
  }

  @SuppressWarnings("unchecked")
  private List<Object> ids(String table) {
    return em.createNativeQuery("SELECT product_id::text AS shown FROM tenant_1_data." + table + " ORDER BY product_id").getResultList();
  }

  @Test
  void anIntegerColumnIsCreatedBigint() throws Exception {
    String table = "t_int_" + System.nanoTime();
    write(table, parsed("1024944", "{\"column\":\"product_id\",\"integer\":true}"));
    write(table, parsed("7.0000", "{\"column\":\"product_id\",\"integer\":true}"));

    assertThat(columnType(table)).isEqualTo("bigint");
    assertThat(ids(table)).as("ordered as numbers, written without a scale").containsExactly("7", "1024944");
  }

  @Test
  void withoutTheOptionTheColumnIsADecimal() throws Exception {
    String table = "t_dec_" + System.nanoTime();
    write(table, parsed("1024944", "{\"column\":\"product_id\"}"));

    assertThat(columnType(table)).isEqualTo("numeric");
    assertThat(ids(table)).containsExactly("1024944.0000");
  }

  @Test
  void aTableAlreadyCreatedKeepsItsTextColumn() throws Exception {
    String table = "t_kept_" + System.nanoTime();
    Map<String, Object> asWritten = new HashMap<>();
    asWritten.put("product_id", "1024944");
    write(table, asWritten);
    write(table, parsed("7", "{\"column\":\"product_id\",\"integer\":true}"));

    assertThat(columnType(table)).isEqualTo("text");
    assertThat(ids(table)).as("text order: the table was not retyped").containsExactly("1024944", "7");
  }
}
