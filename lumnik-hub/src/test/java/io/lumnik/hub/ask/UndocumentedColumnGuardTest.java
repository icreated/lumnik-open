package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class UndocumentedColumnGuardTest {

  private final UndocumentedColumnGuard guard = new UndocumentedColumnGuard();

  /** A card declaring segment + grandtotal + an accented and a currency-signed column, one table. */
  private static SchemaCard card() {
    return new SchemaCard("clients", List.of(
        new SchemaCard.Table("connector.v_customer", "customer", List.of(
            new SchemaCard.Column("segment", "text", "segment"),
            new SchemaCard.Column("grandtotal", "numeric", "grandtotal"),
            new SchemaCard.Column("crédit", "numeric", "crédit"),
            new SchemaCard.Column("montant_€_ht", "numeric", "montant_€_ht")))));
  }

  /**
   * The guard as one table: a query in, and either the column it refuses or nothing.
   *
   * <p>The third column is what makes the empty cells visible — every row states WHICH axis it is
   * exercising, so a shape nobody thought of shows up as an axis with no row rather than as silence.
   * {@code null} means the query is allowed through.
   */
  static Stream<Arguments> verdicts() {
    return Stream.of(
        // ── the advertised job: an invented or internal column filtered against a string ──
        Arguments.of("an invented column", "WHERE loyalty_tier = 'GOLD'", "loyalty_tier"),
        Arguments.of("an invented column in an IN list", "WHERE rank_band IN ('A','B')", "rank_band"),
        Arguments.of("an invented column with <>", "WHERE vip <> 'Y'", "vip"),
        Arguments.of("an internal column the chat was never meant to reason over",
                     "WHERE tenant_id = '5'", "tenant_id"),
        Arguments.of("an invented column behind NOT IN", "WHERE loyalty_tier NOT IN ('A','B')",
                     "loyalty_tier"),
        // ── text containment (#1047): the shape the prompt now asks for must not walk past the guard ──
        Arguments.of("an internal column behind ILIKE", "WHERE \"_row_hash\" ILIKE '%x%'", "_row_hash"),
        Arguments.of("an invented column behind LIKE", "WHERE loyalty_tier LIKE 'G%'", "loyalty_tier"),
        Arguments.of("an invented column behind NOT ILIKE", "WHERE tenant_id NOT ILIKE '%5%'", "tenant_id"),
        Arguments.of("a declared column behind ILIKE", "WHERE \"segment\" ILIKE '%gold%'", null),
        Arguments.of("a declared column behind NOT ILIKE", "WHERE segment NOT ILIKE '%gold%'", null),
        Arguments.of("an invented column carrying an accent", "WHERE société = 'X'", "société"),
        // The same axis one character further out: the lexer used to end a name at any char that was
        // not a letter, so this row refused '_ht' — a fragment, while listing the real column as known.
        Arguments.of("an invented column carrying a currency sign", "WHERE remise_€_ht = '5'",
                     "remise_€_ht"),

        // ── declared columns must pass, whatever the operator ──
        Arguments.of("a declared column", "WHERE segment = 'GOLD'", null),
        Arguments.of("a declared column behind NOT IN", "WHERE segment NOT IN ('GOLD','SILVER')", null),
        Arguments.of("a declared column behind IN", "WHERE segment IN ('GOLD','SILVER')", null),
        Arguments.of("a declared column carrying an accent", "WHERE crédit = 'X'", null),
        Arguments.of("a declared column carrying a currency sign", "WHERE montant_€_ht = '42'", null),
        Arguments.of("a numeric right-hand side, which is usually an alias not a column",
                     "WHERE grandtotal = 100", null),

        // ── the deliberate fail-opens: a miss is allowed, an over-block never is ──
        Arguments.of("a qualified column, whose table we cannot resolve", "t WHERE t.foo = 'X'", null),
        Arguments.of("a function-wrapped column", "WHERE lower(foo) = 'x'", null),
        // Quoted used to be a fail-open, justified by "the card advertises bare names" — which the
        // card stopped doing. A quoted name is now inspected like any other; qualified and
        // function-wrapped remain the deliberate misses.
        Arguments.of("an invented column, quoted", "WHERE \"foo\" = 'X'", "foo"),
        Arguments.of("a declared column, quoted", "WHERE \"segment\" = 'GOLD'", null),
        Arguments.of("a cast target", "WHERE foo::text = 'X'", null),
        Arguments.of("a non-clean IN list", "WHERE foo IN ('A', upper('B'))", null),
        Arguments.of("a keyword left-hand side", "WHERE null = 'X'", null),
        Arguments.of("a niladic value keyword", "WHERE user = 'alice'", null),
        Arguments.of("another niladic value keyword", "WHERE current_role = 'admin'", null),
        // The other direction of those three rows: quoted, a keyword is an ordinary column name to
        // Postgres — and the card hands the model every name quoted, so skipping it as a keyword
        // would let a real filter past the guard under a name a legacy export really does use.
        Arguments.of("a niladic keyword, quoted, so a column name", "WHERE \"user\" = 'alice'", "user"),
        Arguments.of("a quoted keyword that IS declared", "WHERE \"segment\" = 'GOLD'", null),

        // ── a column name is only a column name where SQL says it is ──
        Arguments.of("a filter-shaped string inside a literal",
                     "WHERE segment = 'loyalty_tier = ''X'''", null),
        Arguments.of("a filter-shaped string inside a line comment",
                     "-- loyalty_tier = 'GOLD'\nWHERE segment = 'GOLD'", null),
        Arguments.of("a filter-shaped string inside a block comment",
                     "/* loyalty_tier = 'GOLD' */ WHERE segment = 'GOLD'", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("verdicts")
  void refusesOnlyAnUndeclaredFilteredColumn(String axis, String tail, String refusedColumn) {
    var v = guard.check("SELECT * FROM connector.v_customer " + tail, card());

    if (refusedColumn == null) {
      assertThat(v).as("%s — must pass: over-blocking a legitimate query is the cardinal harm", axis)
          .isEmpty();
    } else {
      assertThat(v).as("%s — must be refused", axis).isPresent();
      assertThat(v.get().column())
          .as("%s — the refusal must name the whole column, not a fragment of it", axis)
          .isEqualTo(refusedColumn);
    }
  }

  /** A HAVING or subquery alias compared to a number is not a column filter. */
  static Stream<Arguments> aliasShapes() {
    return Stream.of(
        Arguments.of("a HAVING alias",
                     "SELECT segment, count(*) c FROM connector.v_customer GROUP BY segment HAVING c = 5"),
        Arguments.of("a subquery alias",
                     "SELECT cnt FROM (SELECT count(*) cnt FROM connector.v_customer) z WHERE cnt = 3"));
  }

  @ParameterizedTest(name = "does not over-block {0}")
  @MethodSource("aliasShapes")
  void doesNotOverBlockAnAlias(String axis, String sql) {
    assertThat(guard.check(sql, card())).as("%s — must pass", axis).isEmpty();
  }

  // ── the message, and the two card shapes the table cannot carry ──────────

  @Test
  void theRefusalTellsTheUserWhatToDoAboutIt() {
    var v = guard.check("SELECT * FROM connector.v_customer WHERE loyalty_tier = 'GOLD'", card());

    assertThat(v).isPresent();
    assertThat(v.get().message())
        .contains("not a declared column")
        .contains("segment")
        .contains("manifest");
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
