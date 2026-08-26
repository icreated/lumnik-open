package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UndocumentedColumnGuardTest {

  private final UndocumentedColumnGuard guard = new UndocumentedColumnGuard();

  /** A card declaring exactly segment + grandtotal on one table. */
  private static SchemaCard card() {
    return new SchemaCard("clients", List.of(
        new SchemaCard.Table("connector.v_customer", "customer", List.of(
            new SchemaCard.Column("segment", "text", "segment"),
            new SchemaCard.Column("grandtotal", "numeric", "grandtotal")))));
  }

  @Test
  void refuses_an_undeclared_column_equality() {
    var v = guard.check("SELECT * FROM connector.v_customer WHERE loyalty_tier = 'GOLD'", card());
    assertThat(v).isPresent();
    assertThat(v.get().column()).isEqualTo("loyalty_tier");
    assertThat(v.get().message()).contains("not a declared column").contains("segment").contains("manifest");
  }

  @Test
  void fails_open_on_a_numeric_rhs_to_avoid_over_blocking_aliases() {
    // A bare `<word> = <number>` is overwhelmingly a SELECT/HAVING/subquery alias, not a column filter,
    // so a numeric RHS is never inspected — over-blocking a legitimate query is the cardinal harm.
    assertThat(guard.check("SELECT * FROM connector.v_customer WHERE tenant_id = 5", card())).isEmpty();
  }

  @Test
  void does_not_over_block_a_having_or_subquery_alias() {
    assertThat(guard.check(
        "SELECT segment, count(*) c FROM connector.v_customer GROUP BY segment HAVING c = 5", card()))
        .isEmpty();
    assertThat(guard.check(
        "SELECT cnt FROM (SELECT count(*) cnt FROM connector.v_customer) z WHERE cnt = 3", card()))
        .isEmpty();
  }

  @Test
  void refuses_an_undeclared_column_in_an_in_list() {
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE rank_band IN ('A','B')", card())).isPresent();
  }

  @Test
  void refuses_an_undeclared_column_with_not_equal() {
    assertThat(guard.check("SELECT * FROM connector.v_customer WHERE vip <> 'Y'", card())).isPresent();
  }

  @Test
  void allows_a_declared_column() {
    assertThat(guard.check("SELECT * FROM connector.v_customer WHERE segment = 'GOLD'", card())).isEmpty();
    assertThat(guard.check("SELECT * FROM connector.v_customer WHERE grandtotal = 100", card())).isEmpty();
  }

  @Test
  void fails_open_on_a_qualified_column() {
    // t.foo — cannot resolve the table without parsing aliases; never over-block.
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer t WHERE t.foo = 'X'", card())).isEmpty();
  }

  @Test
  void fails_open_on_a_function_wrapped_column() {
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE lower(foo) = 'x'", card())).isEmpty();
  }

  @Test
  void fails_open_on_a_quoted_identifier() {
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE \"foo\" = 'X'", card())).isEmpty();
  }

  @Test
  void ignores_a_keyword_left_hand_side() {
    assertThat(guard.check("SELECT * FROM connector.v_customer WHERE null = 'X'", card())).isEmpty();
  }

  @Test
  void ignores_bare_sql_niladic_keywords_as_lhs() {
    // `user` (a synonym of current_user), current_role, etc. are bare SQL value keywords, not columns.
    assertThat(guard.check("SELECT * FROM connector.v_customer WHERE user = 'alice'", card())).isEmpty();
    assertThat(guard.check("SELECT * FROM connector.v_customer WHERE current_role = 'admin'", card())).isEmpty();
  }

  @Test
  void does_not_flag_a_column_name_appearing_inside_a_literal() {
    // The filtered column `segment` is declared; the literal merely CONTAINS "loyalty_tier = X".
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE segment = 'loyalty_tier = ''X'''", card())).isEmpty();
  }

  @Test
  void fails_open_on_a_non_clean_in_list() {
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE foo IN ('A', upper('B'))", card())).isEmpty();
  }

  @Test
  void comment_contents_do_not_trigger_a_refusal() {
    // A filter-like pattern inside an inert SQL comment must NOT be inspected (line and block forms).
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer -- loyalty_tier = 'GOLD'\nWHERE segment = 'GOLD'", card()))
        .isEmpty();
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer /* loyalty_tier = 'GOLD' */ WHERE segment = 'GOLD'", card()))
        .isEmpty();
  }

  @Test
  void fails_open_on_a_cast_target() {
    // `foo::text = 'X'` — `text` is a cast target preceded by ':', not a filtered column; never over-block.
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE foo::text = 'X'", card())).isEmpty();
  }

  @Test
  void empty_card_yields_no_refusal() {
    assertThat(guard.check("SELECT * FROM x WHERE anything = 'Y'",
        new SchemaCard("s", List.of()))).isEmpty();
  }

  @Test
  void truncates_the_known_column_list_for_a_wide_card() {
    List<SchemaCard.Column> cols = new ArrayList<>();
    for (int i = 0; i < 20; i++) cols.add(new SchemaCard.Column("col" + i, "text", "col" + i));
    SchemaCard wide = new SchemaCard("s", List.of(new SchemaCard.Table("connector.v", "v", cols)));

    var v = guard.check("SELECT * FROM connector.v WHERE missing = 'X'", wide);
    assertThat(v).isPresent();
    assertThat(v.get().message()).contains("(+8 more)").doesNotContain("col19"); // 12 shown, 8 elided
    assertThat(v.get().declaredColumns()).hasSize(20);                            // record keeps the full set
  }
}
