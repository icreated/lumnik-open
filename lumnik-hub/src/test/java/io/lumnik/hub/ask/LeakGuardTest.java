package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeakGuardTest {

  private final LeakGuard guard = new LeakGuard();

  /** A card declaring exactly one table: connector.v_customer. */
  private static SchemaCard card() {
    return new SchemaCard("clients", List.of(
        new SchemaCard.Table("connector.v_customer", "customer", List.of(
            new SchemaCard.Column("code", "text", "code")))));
  }

  @Test
  void refuses_an_undeclared_qualified_table_in_from() {
    var v = guard.check("SELECT * FROM connector.t_other WHERE code = 'X'", card());
    assertThat(v).isPresent();
    assertThat(v.get().table()).isEqualTo("connector.t_other");
    assertThat(v.get().message()).contains("not a table in this scope").contains("connector.v_customer");
  }

  @Test
  void refuses_an_undeclared_qualified_table_in_join() {
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer JOIN connector.t_secret ON true", card())).isPresent();
  }

  @Test
  void refuses_an_undeclared_table_inside_a_subquery() {
    assertThat(guard.check(
        "SELECT * FROM (SELECT * FROM connector.t_other) x", card())).isPresent();
  }

  @Test
  void refuses_an_undeclared_table_in_a_comma_join() {
    // The 2nd table of a comma-join is not preceded by FROM/JOIN — must still be caught.
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer, connector.t_secret", card())).isPresent();
  }

  @Test
  void refuses_an_undeclared_table_after_ONLY_or_LATERAL() {
    assertThat(guard.check("SELECT * FROM ONLY connector.t_secret", card())).isPresent();
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer JOIN LATERAL connector.t_secret ON true", card())).isPresent();
  }

  @Test
  void refuses_an_undeclared_qualified_column_read_in_the_select_list() {
    // Reading another table's column is a leak even without a FROM on it.
    assertThat(guard.check(
        "SELECT connector.t_secret.code FROM connector.v_customer", card())).isPresent();
  }

  @Test
  void allows_the_declared_table() {
    assertThat(guard.check("SELECT code FROM connector.v_customer", card())).isEmpty();
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE code = 'X'", card())).isEmpty();
  }

  @Test
  void fails_open_on_a_bare_cte_or_alias() {
    assertThat(guard.check(
        "WITH r AS (SELECT * FROM connector.v_customer) SELECT * FROM r", card())).isEmpty();
    assertThat(guard.check(
        "SELECT t.code FROM connector.v_customer t", card())).isEmpty();
  }

  /**
   * The positive direction, and it became load-bearing when the card started rendering table names
   * quoted: the model now copies {@code "connector"."v_customer"}, so a guard that read only the bare
   * form would refuse EVERY ask. It does not — both sides are folded to lower case before comparing.
   */
  @Test
  void a_declared_table_is_not_refused_when_the_model_quotes_it() {
    assertThat(guard.check("SELECT * FROM \"connector\".\"v_customer\"", card())).isEmpty();
  }

  @Test
  void refuses_a_quoted_or_spaced_qualified_table() {
    // The boundary can't be bypassed by quoting the identifiers or spacing the dot.
    assertThat(guard.check("SELECT * FROM \"connector\".\"t_other\"", card())).isPresent();
    assertThat(guard.check("SELECT * FROM connector . t_other", card())).isPresent();
  }

  @Test
  void refuses_a_three_part_database_qualified_undeclared_table() {
    // db.schema.table: only "db.schema" was ever inspected before, and "db" is never a declared
    // schema, so the real "schema.table" pair escaped the guard entirely. Postgres accepts this
    // form when "db" is the current catalogue, regardless of search_path.
    var v = guard.check("SELECT * FROM lumnik.connector.t_other_scope", card());
    assertThat(v).isPresent();
    assertThat(v.get().table()).isEqualTo("connector.t_other_scope");
  }

  @Test
  void refuses_a_quoted_or_spaced_three_part_undeclared_table() {
    assertThat(guard.check(
        "SELECT * FROM \"lumnik\".\"connector\".\"t_other_scope\"", card())).isPresent();
    assertThat(guard.check("SELECT * FROM lumnik . connector . t_other_scope", card())).isPresent();
  }

  @Test
  void allows_a_three_part_database_qualified_declared_table() {
    // The trailing pair is the declared table — a database-qualified read of an in-scope table
    // is not itself the leak, so it must not be over-blocked.
    assertThat(guard.check("SELECT * FROM lumnik.connector.v_customer", card())).isEmpty();
  }

  @Test
  void enforces_against_a_legacy_unqualified_card() {
    // A card whose table name lacks the schema prefix is normalized to the hub schema, so the boundary
    // still holds — it does not silently skip every connector read.
    SchemaCard legacy = new SchemaCard("s", List.of(
        new SchemaCard.Table("c_order", "order", List.of(new SchemaCard.Column("id", "int", "id")))));
    assertThat(guard.check("SELECT * FROM connector.c_order", legacy)).isEmpty();   // the declared table
    assertThat(guard.check("SELECT * FROM connector.t_other", legacy)).isPresent(); // a cross-scope leak
  }

  @Test
  void does_not_over_block_a_function_FROM_on_the_declared_table() {
    // EXTRACT(... FROM x)/SUBSTRING(... FROM x) reuse the FROM keyword; a qualified operand that is the
    // DECLARED table must not be refused (the cardinal harm). An undeclared one there is itself a leak.
    assertThat(guard.check(
        "SELECT EXTRACT(YEAR FROM connector.v_customer.code) FROM connector.v_customer", card())).isEmpty();
  }

  @Test
  void does_not_flag_a_table_name_inside_a_literal_or_comment() {
    assertThat(guard.check(
        "SELECT code FROM connector.v_customer WHERE code = 'from connector.t_other'", card())).isEmpty();
    assertThat(guard.check(
        "SELECT code FROM connector.v_customer /* FROM connector.t_other */", card())).isEmpty();
  }

  @Test
  void empty_card_yields_no_refusal() {
    assertThat(guard.check("SELECT * FROM connector.anything", new SchemaCard("s", List.of()))).isEmpty();
  }
}
