package io.lumnik.hub.entity;

import io.lumnik.hub.view.ColumnKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        List.of("_entity_id", "_resolution", "_handle"), List.of(), Map.of());
    assertThat(sql).endsWith("ORDER BY \"code\", \"_entity_id\", \"_handle\"");
  }

  @Test
  void the_default_read_orders_as_before() {
    assertThat(EntityReadResource.selectSql(M, List.of("code"), List.of(), List.of(), Map.of()))
        .endsWith("ORDER BY \"code\"");
  }

  private static EntityManifest inZone(String zone) {
    return new EntityManifest("Entity", "customer", null, null,
        List.of(new EntityManifest.Source("t_crm", Map.of("due", "due"))), null, null, null, null, null, zone);
  }

  private static String filterSql(EntityManifest m, String value) {
    return filterSql(m, value, ColumnKind.DATETIME);
  }

  private static String filterSql(EntityManifest m, String value, ColumnKind kind) {
    return EntityReadResource.selectSql(m, List.of("due"), List.of(),
        List.of(new EntityReadResource.ColumnFilter("due", value)), Map.of("due", kind));
  }

  @Test
  void a_day_filter_is_that_day_s_midnight_in_the_entity_zone() {
    assertThat(filterSql(inZone("Europe/Paris"), "2026-07-01"))
        .contains("\"due\"::timestamptz = (CAST(?1 AS date)::timestamp AT TIME ZONE 'Europe/Paris')");
  }

  @Test
  void a_day_filter_without_a_zone_stays_midnight_utc() {
    assertThat(filterSql(M, "2026-07-01"))
        .contains("\"due\"::timestamptz = (CAST(?1 AS date)::timestamp AT TIME ZONE 'UTC')");
  }

  @Test
  void an_instant_filter_is_not_moved_by_the_zone() {
    assertThat(filterSql(inZone("Europe/Paris"), "2026-07-01T10:00:00Z"))
        .contains("\"due\"::timestamptz = CAST(?1 AS timestamptz)");
  }

  @Test
  void a_day_filter_on_a_date_column_is_that_date_whatever_the_zone() {
    assertThat(filterSql(inZone("Europe/Paris"), "2026-07-01", ColumnKind.DATE))
        .contains("\"due\" = CAST(?1 AS date)")
        .doesNotContain("AT TIME ZONE");
  }

  @Test
  void an_instant_filter_on_a_date_column_is_still_an_instant() {
    assertThat(filterSql(inZone("Europe/Paris"), "2026-07-01T10:00:00Z", ColumnKind.DATE))
        .contains("\"due\"::timestamptz = CAST(?1 AS timestamptz)");
  }
}
