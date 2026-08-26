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
class DynamicTableWriterIT {

  @Inject DynamicTableWriter writer;
  @Inject EntityManager em;
  @Inject UserTransaction utx;

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
}
