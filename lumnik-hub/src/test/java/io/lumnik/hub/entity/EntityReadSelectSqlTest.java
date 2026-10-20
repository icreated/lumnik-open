package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offset paging needs a total order (#1125 review): canonical columns need not be unique, so an
 * identity read — the one that carries a per-row key — ends its ORDER BY on {@code _entity_id, _handle}: a resolved row is
 * one entity id (its handle is NULL), an unresolved one one handle.
 */
class EntityReadSelectSqlTest {

  private static final EntityManifest M = new EntityManifest("Entity", "customer", List.of("code"),
      List.of(new EntityManifest.Source("t_crm", Map.of("code", "code"))));

  @Test
  void an_identity_read_breaks_canonical_ties_on_the_identity() {
    String sql = EntityReadResource.selectSql(M, List.of("code"),
        List.of("_entity_id", "_resolution", "_handle"), List.of(), Set.of());
    assertThat(sql).endsWith("ORDER BY \"code\", \"_entity_id\", \"_handle\"");
  }

  @Test
  void the_default_read_orders_as_before() {
    assertThat(EntityReadResource.selectSql(M, List.of("code"), List.of(), List.of(), Set.of()))
        .endsWith("ORDER BY \"code\"");
  }
}
