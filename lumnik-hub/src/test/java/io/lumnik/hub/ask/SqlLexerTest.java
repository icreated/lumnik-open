package io.lumnik.hub.ask;

import io.lumnik.hub.ask.SqlLexer.Kind;
import io.lumnik.hub.ask.SqlLexer.Token;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SqlLexerTest {

  @Test
  void tokenizes_ident_operator_string() {
    var t = SqlLexer.tokenize("a = 'x'");
    assertThat(t).extracting(Token::kind, Token::text)
        .containsExactly(tuple(Kind.IDENT, "a"), tuple(Kind.OP, "="), tuple(Kind.STRING, "x"));
    assertThat(t.get(0).quoted()).isFalse();
  }

  @Test
  void unescapes_a_doubled_quote_in_a_string() {
    var t = SqlLexer.tokenize("'O''X'");
    assertThat(t).extracting(Token::kind, Token::text).containsExactly(tuple(Kind.STRING, "O'X"));
  }

  @Test
  void marks_a_quoted_identifier() {
    var t = SqlLexer.tokenize("\"col\"");
    assertThat(t.get(0).kind()).isEqualTo(Kind.IDENT);
    assertThat(t.get(0).text()).isEqualTo("col");
    assertThat(t.get(0).quoted()).isTrue();
  }

  @Test
  void skips_line_and_block_comments() {
    var t = SqlLexer.tokenize("a -- c = 'x'\n , /* b = 'y' */ d");
    assertThat(t).extracting(Token::text).containsExactly("a", ",", "d");
  }

  @Test
  void distinguishes_cast_dot_and_comparison_operators() {
    var t = SqlLexer.tokenize("a::b.c <> d != e");
    assertThat(t).extracting(Token::kind, Token::text).containsExactly(
        tuple(Kind.IDENT, "a"), tuple(Kind.OP, "::"), tuple(Kind.IDENT, "b"), tuple(Kind.PUNCT, "."),
        tuple(Kind.IDENT, "c"), tuple(Kind.OP, "<>"), tuple(Kind.IDENT, "d"),
        tuple(Kind.OP, "!="), tuple(Kind.IDENT, "e"));
  }

  @Test
  void reads_a_number_not_a_string() {
    var t = SqlLexer.tokenize("x = 3.14");
    assertThat(t.get(2).kind()).isEqualTo(Kind.NUMBER);
    assertThat(t.get(2).text()).isEqualTo("3.14");
  }

  @Test
  void spacing_around_a_dot_is_irrelevant() {
    assertThat(SqlLexer.tokenize("a.b")).extracting(Token::text).containsExactly("a", ".", "b");
    assertThat(SqlLexer.tokenize("a . b")).extracting(Token::text).containsExactly("a", ".", "b");
  }

  @Test
  void tolerates_garbage_and_null_without_throwing() {
    assertThat(SqlLexer.tokenize("@#% a")).extracting(Token::text).contains("a");
    assertThat(SqlLexer.tokenize(null)).isEmpty();
    assertThat(SqlLexer.tokenize("")).isEmpty();
  }

  @Test
  void does_not_overflow_the_stack_on_a_huge_literal() {
    var t = SqlLexer.tokenize("a = '" + "x".repeat(500_000) + "'");
    assertThat(t.get(2).kind()).isEqualTo(Kind.STRING);
    assertThat(t.get(2).text()).hasSize(500_000);
  }

  @Test
  void identifiers_and_numbers_are_ascii_only_to_match_the_guards_prior_regex() {
    // The old guards used [a-z_][a-z0-9_]* / [0-9]: a non-ASCII char ends the identifier, so a non-ASCII
    // column is never cleanly recognized — fail-open preserved (no NEW refusal in this pure refactor).
    var t = SqlLexer.tokenize("société = 'x'");
    assertThat(t.get(0).kind()).isEqualTo(Kind.IDENT);
    assertThat(t.get(0).text()).isEqualTo("soci"); // stops at the non-ASCII 'é'
    // a non-ASCII (Arabic-Indic) digit is likewise not a NUMBER
    assertThat(SqlLexer.tokenize("١").get(0).kind()).isNotEqualTo(Kind.NUMBER);
  }
}
