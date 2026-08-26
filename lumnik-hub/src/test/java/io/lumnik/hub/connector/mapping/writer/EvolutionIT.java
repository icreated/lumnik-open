package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class EvolutionIT {

  @Inject DynamicTableWriter writer;
  @Inject EntityManager em;
  @Inject UserTransaction utx;

  @Test
  void alters_table_when_new_column_appears() throws Exception {
    String mappingId = "test_evolve_" + System.nanoTime();
    MappingTarget.Table spec = new MappingTarget.Table(
        "tenant_1_data", mappingId, true, MappingTarget.OnConflict.SKIP, List.of());

    Map<String, Object> row1 = Map.of("email", "a@x.com");
    Map<String, Object> sys1 = Map.of(
        "_row_hash", "h1_" + mappingId, "_tenant", 1L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

    TenantContext.runAsSystem(() -> {
      try { utx.begin(); writer.write(spec, List.of(row1), List.of(sys1), 1L); utx.commit(); }
      catch (Exception e) { throw new RuntimeException(e); }
    });

    Map<String, Object> row2 = Map.of("email", "b@x.com", "phone", "0123456789");
    Map<String, Object> sys2 = Map.of(
        "_row_hash", "h2_" + mappingId, "_tenant", 1L, "_mapping", mappingId,
        "_run_id", UUID.randomUUID(), "_mapping_version", 1, "_source", "test");

    TenantContext.runAsSystem(() -> {
      try { utx.begin(); writer.write(spec, List.of(row2), List.of(sys2), 1L); utx.commit(); }
      catch (Exception e) { throw new RuntimeException(e); }
    });

    String phone = (String) em.createNativeQuery(
            "SELECT phone FROM tenant_1_data." + mappingId + " WHERE _row_hash = ?1")
        .setParameter(1, "h2_" + mappingId)
        .getSingleResult();
    assertThat(phone).isEqualTo("0123456789");
  }
}
