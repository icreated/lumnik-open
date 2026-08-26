package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DomainGuardTest {

  private final DomainGuard guard = new DomainGuard();

  private static SchemaCard cardWithSegment() {
    return new SchemaCard("clients", List.of(
        new SchemaCard.Table("connector.v_customer", "customer", List.of(
            new SchemaCard.Column("segment", "text", "segment", null, null,
                List.of("GOLD", "SILVER", "BRONZE"))),
            null)));
  }

  @Test
  void flags_out_of_domain_equality_with_actionable_message() {
    Optional<DomainGuard.Violation> v = guard.check(
        "SELECT count(*) FROM connector.v_customer WHERE segment = 'PLATINUM'", cardWithSegment());
    assertThat(v).isPresent();
    assertThat(v.get().column()).isEqualTo("segment");
    assertThat(v.get().value()).isEqualTo("PLATINUM");
    assertThat(v.get().message()).contains("not a declared value").contains("GOLD").contains("manifest");
  }

  @Test
  void message_escapes_a_quote_in_the_value() {
    DomainGuard.Violation v = new DomainGuard.Violation("segment", "O'X", List.of("GOLD"));
    assertThat(v.message()).contains("'O''X'"); // SQL-style escaped — unambiguous, copy-pasteable
  }

  @Test
  void allows_in_domain_value() {
    assertThat(guard.check(
        "SELECT count(*) FROM connector.v_customer WHERE segment = 'GOLD'", cardWithSegment())).isEmpty();
  }

  @Test
  void flags_out_of_domain_inside_in_list() {
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE segment IN ('GOLD','PLATINUM')", cardWithSegment()))
        .isPresent();
  }

  @Test
  void allows_in_list_all_in_domain() {
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE segment IN ('GOLD','SILVER')", cardWithSegment())).isEmpty();
  }

  @Test
  void column_without_declared_values_is_never_flagged() {
    SchemaCard card = new SchemaCard("s", List.of(
        new SchemaCard.Table("connector.v_customer", "customer", List.of(
            new SchemaCard.Column("city", "text", "city")))));
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE city = 'Atlantis'", card)).isEmpty();
  }

  @Test
  void does_not_over_block_when_domain_column_name_is_inside_another_literal() {
    // A filter on a non-domain column whose VALUE text contains "segment = 'PLATINUM'". segment is
    // never actually filtered — refusing here would block a legitimate query (the worst outcome).
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE name = 'segment = ''PLATINUM'''", cardWithSegment()))
        .isEmpty();
  }

  @Test
  void handles_escaped_quote_in_the_literal_value() {
    Optional<DomainGuard.Violation> v = guard.check(
        "SELECT * FROM connector.v_customer WHERE segment = 'O''X'", cardWithSegment());
    assertThat(v).isPresent();
    assertThat(v.get().value()).isEqualTo("O'X"); // unescaped, exact
  }

  @Test
  void fails_open_on_non_clean_in_list_containing_a_function() {
    // Not a clean `col IN ('a','b')` list — a function nests inside the parens. Do not over-block.
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE segment IN ('GOLD', upper('PLATINUM'))", cardWithSegment()))
        .isEmpty();
  }

  @Test
  void does_not_inspect_quoted_identifiers_no_over_block() {
    // A double-quoted identifier is not a bare column token: the closing quote sits between the
    // column word and the operator, so the full match breaks — not inspected (a miss, never an
    // over-block), consistent with the javadoc. Also covers a quoted identifier that merely CONTAINS
    // the domain column name as a word ("segment value").
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE \"segment\" = 'PLATINUM'", cardWithSegment())).isEmpty();
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE \"segment value\" = 'PLATINUM'", cardWithSegment())).isEmpty();
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE \"segment\" IN ('PLATINUM')", cardWithSegment())).isEmpty();
  }

  @Test
  void fails_open_when_a_column_name_has_divergent_domains_across_tables() {
    // Two tables both declare `segment` with DIFFERENT domains. A bare `segment` can't be resolved to
    // a table without parsing FROM/aliases, so the guard must NOT enforce either (no over-block).
    SchemaCard card = new SchemaCard("clients", List.of(
        new SchemaCard.Table("connector.v_a", "a", List.of(
            new SchemaCard.Column("segment", "text", "segment", null, null, List.of("GOLD", "SILVER"))), null),
        new SchemaCard.Table("connector.v_b", "b", List.of(
            new SchemaCard.Column("segment", "text", "segment", null, null, List.of("BRONZE", "PLATINUM"))), null)));
    assertThat(guard.check("SELECT * FROM connector.v_a WHERE segment = 'GOLD'", card)).isEmpty();
  }

  @Test
  void still_enforces_when_a_name_has_one_identical_domain_across_tables() {
    SchemaCard card = new SchemaCard("clients", List.of(
        new SchemaCard.Table("connector.v_a", "a", List.of(
            new SchemaCard.Column("segment", "text", "segment", null, null, List.of("GOLD", "SILVER"))), null),
        new SchemaCard.Table("connector.v_b", "b", List.of(
            new SchemaCard.Column("segment", "text", "segment", null, null, List.of("GOLD", "SILVER"))), null)));
    assertThat(guard.check("SELECT * FROM connector.v_a WHERE segment = 'PLATINUM'", card)).isPresent();
  }

  @Test
  void fails_open_on_function_wrapped_column() {
    // lower(segment) = 'platinum' is ambiguous — we must NOT block it (better to miss than over-block).
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE lower(segment) = 'platinum'", cardWithSegment())).isEmpty();
  }

  @Test
  void does_not_overflow_the_stack_on_a_huge_string_literal() {
    // The literal-masking regex must consume a long value without per-character recursion in the engine
    // (S5998): the old `'((?:[^']|'')*)'` recursed once per char and overflowed the stack on big inputs.
    String huge = "x".repeat(200_000);
    assertThat(guard.check(
        "SELECT * FROM connector.v_customer WHERE segment = '" + huge + "'", cardWithSegment())).isPresent();
  }
}
