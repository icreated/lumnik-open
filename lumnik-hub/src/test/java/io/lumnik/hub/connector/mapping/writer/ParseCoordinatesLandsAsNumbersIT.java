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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** #1966: a pair read with {@code parse-coordinates} creates two {@code numeric} columns, compared as numbers. */
@QuarkusTest
class ParseCoordinatesLandsAsNumbersIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject DynamicTableWriter writer;
  @Inject HookCatalog catalog;
  @Inject EntityManager em;
  @Inject UserTransaction utx;

  private Map<String, Object> parsed(String site, String lat, String lon) throws Exception {
    JsonNode p = JSON.readTree("{\"lat\":\"latitude\",\"lon\":\"longitude\",\"within\":[41.0,-5.5,51.5,10.0],"
        + "\"swapped\":\"fix\",\"reason\":\"coord_issue\"}");
    var hook = catalog.cell("parse-coordinates");
    hook.validateParams(p);
    Map<String, Object> row = new HashMap<>();
    row.put("site", site);
    row.put("latitude", lat);
    row.put("longitude", lon);
    hook.apply(row, p, new MappingState());
    return row;
  }

  /** One chunk: the rows are written together, as a run writes them. */
  private void write(String table, List<Map<String, Object>> rows) {
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", table, true, MappingTarget.OnConflict.SKIP, List.of());
    List<Map<String, Object>> sys = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      sys.add(Map.of("_row_hash", "h_" + table + "_" + i, "_tenant", 1L, "_mapping", table,
          "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test"));
    }
    TenantContext.runAsSystem(() -> {   // a failed write's open transaction is DanglingTransactionNet's to roll back
      try {
        utx.begin();
        writer.write(spec, rows, sys, 1L);
        utx.commit();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  private Object typeOf(String table, String column) {
    return em.createNativeQuery("SELECT data_type FROM information_schema.columns "
            + "WHERE table_schema = 'tenant_1_data' AND table_name = ?1 AND column_name = ?2")
        .setParameter(1, table).setParameter(2, column).getSingleResult();
  }

  @Test
  void aPairLandsAsTwoNumbersBesideAnEmptiedOne() throws Exception {
    String table = "t_coords_" + System.nanoTime();
    write(table, List.of(
        parsed("S08", "574357.82", "6279642.97"),      // emptied first: the columns are still numbers
        parsed("S03", "48°50'12\"N", "2°22'58\"E"),
        parsed("S01", "45.7302", "4.8309"),
        parsed("S05", "5.3698", "43.2965")));

    assertThat(typeOf(table, "latitude")).isEqualTo("numeric");
    assertThat(typeOf(table, "longitude")).isEqualTo("numeric");
    assertThat(typeOf(table, "coord_issue")).isEqualTo("text");
    assertThat(em.createNativeQuery("SELECT site FROM tenant_1_data." + table + " WHERE latitude > 48")
        .getResultList()).containsExactly("S03");
    assertThat(em.createNativeQuery("SELECT coord_issue FROM tenant_1_data." + table + " ORDER BY site")
        .getResultList()).containsExactly(null, null, "swapped: exchanged", "not degrees");
  }
}
