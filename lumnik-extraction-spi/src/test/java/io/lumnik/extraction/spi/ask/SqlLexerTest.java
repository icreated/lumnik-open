package io.lumnik.extraction.spi.ask;

import io.lumnik.extraction.spi.ask.SqlLexer.Kind;
import io.lumnik.extraction.spi.ask.SqlLexer.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  /**
   * This test used to assert {@code société -> "soci"} and call the truncation "fail-open preserved".
   * The claim was true only of that one sample: {@code société} ends on its accent, so the fragment left
   * against the {@code =} was an operator and no filter was built. Move the accent one letter inwards and
   * the tail becomes a bare identifier the guards happily refuse — {@code crédit = 'X'} was refused as a
   * column named {@code dit}. The row that mattered is the SECOND one below.
   */
  @ParameterizedTest(name = "{0} is one identifier")
  @CsvSource({
      "an accent in last position,   société",
      "an accent in the middle,      crédit",
      "letters outside Latin-1,      Łódź",
      "an accent and an underscore,  naïve_col",
      "plain ASCII,                  segment",
  })
  void anIdentifierIsOneTokenWhereverItsAccentsFall(String axis, String name) {
    var t = SqlLexer.tokenize(name + " = 'x'");

    assertThat(t).as("%s — %s must not be split", axis, name).hasSize(3);
    assertThat(t.get(0).kind()).isEqualTo(Kind.IDENT);
    assertThat(t.get(0).text()).as("%s — the whole name, not a fragment", axis).isEqualTo(name);
    assertThat(t.get(1).text()).isEqualTo("=");
    assertThat(t.get(2).kind()).isEqualTo(Kind.STRING);
  }

  @Test
  void numbers_stay_ascii_so_a_foreign_digit_is_never_read_as_a_value() {
    assertThat(SqlLexer.tokenize("١").get(0).kind()).isNotEqualTo(Kind.NUMBER);
  }
}
