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

/** #1760: a CSV flag read with {@code parse-boolean} creates a {@code boolean} column, filtered as one. */
@QuarkusTest
class ParseBooleanLandsAsBooleanIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject DynamicTableWriter writer;
  @Inject HookCatalog catalog;
  @Inject EntityManager em;
  @Inject UserTransaction utx;

  private Map<String, Object> parsed(String raw) throws Exception {
    JsonNode p = JSON.readTree("{\"column\":\"actif\"}");
    var hook = catalog.cell("parse-boolean");
    hook.validateParams(p);
    Map<String, Object> row = new HashMap<>();
    row.put("actif", raw);
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

  @Test
  void aFlagColumnIsCreatedBoolean() throws Exception {
    String table = "t_bool_" + System.nanoTime();
    write(table, parsed("1"));
    write(table, parsed("Non"));
    write(table, parsed("O"));

    assertThat(em.createNativeQuery("SELECT data_type FROM information_schema.columns "
            + "WHERE table_schema = 'tenant_1_data' AND table_name = ?1 AND column_name = 'actif'")
        .setParameter(1, table).getSingleResult()).isEqualTo("boolean");
    assertThat(((Number) em.createNativeQuery("SELECT count(*) FROM tenant_1_data." + table + " WHERE actif")
        .getSingleResult()).longValue()).isEqualTo(2);
  }
}
