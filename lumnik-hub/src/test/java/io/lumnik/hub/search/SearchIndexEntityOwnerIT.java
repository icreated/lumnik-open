package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.scheduler.TenantScope;
import io.lumnik.hub.search.SearchIndexStore.ApplyOutcome;
import io.lumnik.hub.search.projection.ProjectionDefinitions;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2050: a SearchIndex binds to the Entity version its door validated, and to its owner. The door read an Entity this
 * tenant reads; before the write, the name is deleted and applied again by another tenant, with the same YAML. The write
 * re-asks in its own statement, and another tenant's private Entity is not the one validated.
 */
@QuarkusTest
class SearchIndexEntityOwnerIT {

  private static final String ENTITY = "t2050idxentity";
  private static final String INDEX = "t2050-idx";
  private static final String YAML = "kind: Entity\nname: " + ENTITY + "\nmatch: [code]\n";

  @Inject SearchIndexStore store;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  private final SearchIndexManifestParser parser = new SearchIndexManifestParser();

  @AfterEach
  void cleanUp() {
    Jdbc.update(adminDs, "test.cleanup", "DELETE FROM core.search_index WHERE name = ?", ps -> ps.setString(1, INDEX));
    Jdbc.update(adminDs, "test.cleanup", "DELETE FROM connector.entity_manifest WHERE name = ?",
        ps -> ps.setString(1, ENTITY));
  }

  @Test
  void the_same_yaml_owned_by_another_tenant_is_not_indexed() {
    storeEntityOwnedBy(2L);

    assertThat(upsertAsTenant1()).isEqualTo(ApplyOutcome.ENTITY_CHANGED);
    assertThat(Jdbc.exists(adminDs, "test.indexed", "SELECT 1 FROM core.search_index WHERE name = ?",
        ps -> ps.setString(1, INDEX))).isFalse();
  }

  /** The control: the same write over an Entity the tenant reads goes through. */
  @Test
  void the_same_yaml_owned_by_the_writer_is_indexed() {
    storeEntityOwnedBy(1L);

    assertThat(upsertAsTenant1()).isEqualTo(ApplyOutcome.APPLIED);
  }

  private void storeEntityOwnedBy(long owner) {
    Jdbc.update(adminDs, "test.seed", "INSERT INTO connector.entity_manifest (name, yaml_text, scopes, owner_tenant_id) "
        + "VALUES (?, ?, '{}', ?)", ps -> {
          ps.setString(1, ENTITY);
          ps.setString(2, YAML);
          ps.setLong(3, owner);
        });
  }

  private ApplyOutcome upsertAsTenant1() {
    SearchIndexManifest m = parser.parse(parser.readTree(searchIndex(INDEX, ENTITY, "code")));
    return TenantScope.get(1L, () -> QuarkusTransaction.requiringNew().call(() -> store.upsert(1L, INDEX, ENTITY, YAML,
        m.language(), "kind: SearchIndex", "{}", ProjectionDefinitions.from(m), null, null))).outcome();
  }
}
