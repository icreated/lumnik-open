package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.HubRelationProjection.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2062: a shared {@code connector.t_*} table holds every tenant's columns; Ask's SQL reads it through a projection of
 * the tenant's card only, so PostgreSQL itself never shows another tenant's column, whatever shape the query takes.
 */
class HubRelationProjectionTest {

  private static final SchemaCard CARD = new SchemaCard("sales", List.of(
      new SchemaCard.Table("connector.t_orders", "t_orders", List.of(
          new SchemaCard.Column("code", "text", "code"), new SchemaCard.Column("amount", "numeric", "amount"))),
      new SchemaCard.Table("connector.v_customer", "customer", List.of(new SchemaCard.Column("name", "text", "name"))),
      new SchemaCard.Table("ext.orders_api", "orders_api", List.of(new SchemaCard.Column("external_id", "text", "id")))));

  /** What the table holds: the card's columns, and another tenant's. */
  private static final Function<String, Set<String>> HELD =
      t -> t.endsWith("distance_m") || t.equals("ext.code") ? Set.of() : Set.of("code", "amount", "margin", "external_id");

  private static final String PROJECTED = "(SELECT \"code\", \"amount\" FROM connector.\"t_orders\")";

  private static String rewritten(String sql) {
    return ((Outcome.Rewritten) HubRelationProjection.rewrite(sql, CARD, HELD)).sql();
  }

  @Test
  void a_star_reads_the_cards_columns_only() {
    assertThat(rewritten("SELECT * FROM connector.t_orders LIMIT 10"))
        .isEqualTo("SELECT * FROM " + PROJECTED + " AS \"t_orders\" LIMIT 10");
  }

  @Test
  void an_alias_is_kept_and_its_star_and_row_to_json_see_the_projection() {
    assertThat(rewritten("SELECT o.*, row_to_json(o) FROM connector.t_orders o"))
        .isEqualTo("SELECT o.*, row_to_json(o) FROM " + PROJECTED + " AS \"o\"");
    assertThat(rewritten("SELECT x.code FROM connector.t_orders AS x"))
        .isEqualTo("SELECT x.code FROM " + PROJECTED + " AS \"x\"");
  }

  @Test
  void a_join_a_sub_query_and_a_cte_are_each_projected() {
    assertThat(rewritten("WITH big AS (SELECT code FROM connector.t_orders WHERE amount > 9) "
        + "SELECT c.name FROM connector.v_customer c JOIN connector.t_orders o ON o.code = c.name, big"))
        .isEqualTo("WITH big AS (SELECT code FROM " + PROJECTED + " AS \"t_orders\" WHERE amount > 9) "
            + "SELECT c.name FROM connector.v_customer c JOIN " + PROJECTED + " AS \"o\" ON o.code = c.name, big");
  }

  /** A name off the card stays as written: PostgreSQL answers it as a column that does not exist. */
  @Test
  void a_column_off_the_card_is_left_to_the_database() {
    assertThat(rewritten("SELECT margin FROM connector.t_orders"))
        .isEqualTo("SELECT margin FROM " + PROJECTED + " AS \"t_orders\"");
  }

  /** A card behind its table names a column the table lost: left out, not a failure of every query. */
  @Test
  void a_card_column_the_table_lost_is_left_out() {
    assertThat(((Outcome.Rewritten) HubRelationProjection.rewrite("SELECT * FROM connector.t_orders", CARD,
        t -> Set.of("code"))).sql()).isEqualTo("SELECT * FROM (SELECT \"code\" FROM connector.\"t_orders\") AS \"t_orders\"");
  }

  @Test
  void a_function_of_the_hubs_schema_is_no_relation() {
    assertThat(rewritten("SELECT connector.distance_m(1, 2, 3, 4) FROM connector.v_customer"))
        .isEqualTo("SELECT connector.distance_m(1, 2, 3, 4) FROM connector.v_customer");
  }

  /** A hybrid REST table under ext is shared too. */
  @Test
  void an_ext_table_is_projected_too() {
    assertThat(rewritten("SELECT * FROM ext.orders_api"))
        .isEqualTo("SELECT * FROM (SELECT \"external_id\" FROM ext.\"orders_api\") AS \"orders_api\"");
  }

  /** A comma join after an AS alias, or after a JOIN's ON: still a relation of the FROM clause. */
  @Test
  void a_comma_join_after_an_as_alias_or_an_on_is_a_relation() {
    assertThat(rewritten("SELECT 1 FROM connector.v_customer AS c, connector.t_orders"))
        .isEqualTo("SELECT 1 FROM connector.v_customer AS c, " + PROJECTED + " AS \"t_orders\"");
    assertThat(rewritten("SELECT 1 FROM connector.v_customer c JOIN connector.v_customer d ON true, connector.t_orders"))
        .isEqualTo("SELECT 1 FROM connector.v_customer c JOIN connector.v_customer d ON true, " + PROJECTED
            + " AS \"t_orders\"");
  }

  /** An alias named like a hub schema, followed by a column: no table, read as it is. */
  @Test
  void an_alias_named_ext_is_no_table() {
    assertThat(rewritten("SELECT ext.code FROM connector.v_customer ext")).isEqualTo("SELECT ext.code FROM connector.v_customer ext");
  }

  /** In a FROM list after a sub-query and a comma: still a relation. */
  @Test
  void a_comma_after_a_sub_query_in_from_is_a_relation() {
    assertThat(rewritten("SELECT 1 FROM (SELECT 1) s, connector.t_orders"))
        .isEqualTo("SELECT 1 FROM (SELECT 1) s, " + PROJECTED + " AS \"t_orders\"");
  }

  @Test
  void an_entity_view_is_read_as_it_is() {
    assertThat(rewritten("SELECT name FROM connector.v_customer")).isEqualTo("SELECT name FROM connector.v_customer");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT connector.t_orders.code FROM connector.t_orders",   // a column named through its table's full name
      "SELECT * FROM ONLY connector.t_orders",                     // ONLY reads no sub-query
      "SELECT * FROM connector.t_other",                           // not on the card
      "SELECT * FROM lumnik.connector.t_orders",                   // a catalog-qualified name
      "SELECT a FROM connector.t_orders o(a, b)",                  // its columns renamed
      "SELECT (NULL::connector.t_orders).*",                       // its row type, every physical column
      "SELECT to_jsonb(jsonb_populate_record(NULL::connector.t_orders, '{}'))",
      "SELECT CAST(NULL AS connector.t_orders)",
      "SELECT (NULL::connector.v_customer).*",                     // an Entity view's row type too
      "SELECT 1, connector.t_orders FROM connector.t_orders",      // a whole row in the select list
      "SELECT coalesce(1, connector.t_orders) FROM connector.v_customer",
      "SELECT * FROM connector.t_orders TABLESAMPLE SYSTEM (10)",  // nothing to sample on a sub-query
      "SELECT * FROM connector.t_orders * ",                       // inheritance
      "SELECT connector.t_orders(NULL)",                           // a functional cast: its row type
      "SELECT * FROM connector.t_orders JOIN ext.t_orders ON true"})  // two unaliased tables, one alias
  void a_shape_it_cannot_rewrite_is_refused(String sql) {
    assertThat(HubRelationProjection.rewrite(sql, CARD, HELD)).isInstanceOf(Outcome.Refused.class);
  }
}
