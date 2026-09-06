package io.lumnik.hub.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code isPlainIdentifier} and {@code isPlainQualifiedIdentifier} — the STRICTER of the two
 * checks in {@link SqlIdentifiers}, and the one with no test until now.
 *
 * <p>The distinction the class comment draws is the whole reason this matters. {@code quoteIdent}
 * is the escaping defence and must ACCEPT what this predicate rejects — {@code t-cust} is a valid
 * Postgres identifier. This predicate guards the other thing: identifiers the hub COMPOSES by
 * string concatenation ({@code "t_" + name}) before handing them to DDL, where quoting cannot
 * help because the concatenation itself has to be safe. Every table-name door depends on it —
 * {@code JdbcConnectorType}, {@code CsvFileConnectorType}, {@code KafkaConnectorType},
 * {@code MappingLoader}, {@code MapColumnsRules} among them.
 *
 * <p>Two kinds of test, and they do different jobs. The tables say what the rule MEANS, one row
 * per decision worth reading; the sweep says that nothing else slipped through, over every BMP
 * character in every position. A table only ever contains what its author thought of — proved by
 * neutralising the predicate to {@code Character.isLetter}, which the sweep answered with U+00AA,
 * the FEMININE ORDINAL INDICATOR, absent from every row below.
 */
class PlainIdentifierTest {

  /**
   * The rule as a regex, kept here as the ORACLE for the sweep below — a second, independent
   * implementation used differentially, not a copy of the documentation. It is what makes
   * production's hand-written loop checkable rather than merely reviewed; why production does not
   * simply use it is recorded on the method itself.
   */
  private static final Pattern ORACLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  @ParameterizedTest(name = "{0}")
  @MethodSource("identifiers")
  void the_plain_identifier_contract(String label, String ident, boolean expected) {
    assertThat(SqlIdentifiers.isPlainIdentifier(ident)).as("%s", label).isEqualTo(expected);
  }

  static Stream<Arguments> identifiers() {
    return Stream.of(
        Arguments.of("a snake_case column", "customer_id", true),
        Arguments.of("upper case, as legacy ERPs export it", "CUST_NM", true),
        Arguments.of("a leading underscore", "_internal", true),
        Arguments.of("digits after the first character", "line_no_2", true),
        Arguments.of("a single letter", "x", true),
        Arguments.of("an underscore alone", "_", true),
        // The literal name String.valueOf(null) produces — MapColumnsRules refuses a null rename
        // target precisely BECAUSE this predicate would then accept the result.
        Arguments.of("the word null, which is a perfectly plain identifier", "null", true),

        Arguments.of("null", null, false),
        Arguments.of("the empty string", "", false),
        Arguments.of("a leading digit", "2fast", false),
        Arguments.of("a space", "not a column", false),
        // Valid Postgres, and quoteIdent must keep accepting it — see the class comment.
        Arguments.of("a hyphen: quotable, but not composable", "t-cust", false),
        Arguments.of("an embedded double quote", "a\"b", false),
        Arguments.of("a semicolon", "t;DROP TABLE x", false),
        Arguments.of("a dot — qualified names go through the other method", "connector.t_x", false),

        // The character-class trap: these are letters and digits to Character.isLetterOrDigit,
        // and Postgres would even accept some of them unquoted. The rule is ASCII, on purpose.
        Arguments.of("an accented letter", "café", false),
        Arguments.of("a Cyrillic lookalike", "аdmin", false),
        Arguments.of("a full-width digit", "a１", false),
        Arguments.of("an Arabic-Indic digit", "a٢", false),
        Arguments.of("an emoji, which is a surrogate pair", "a😀", false),

        // The matches()-versus-find() trap that bit the mojibake hook, named once. The other
        // placements of a newline are not listed: U+000A is inside the sweep's range, so it
        // already tests this character in every position — a row for each would be the sweep
        // rewritten by hand, which is the weaker of the two proofs.
        Arguments.of("a trailing newline", "customer_id\n", false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("qualifiedIdentifiers")
  void the_qualified_contract_splits_on_the_first_dot_only(String label, String fqn,
      boolean expected) {
    assertThat(SqlIdentifiers.isPlainQualifiedIdentifier(fqn)).as("%s", label).isEqualTo(expected);
  }

  static Stream<Arguments> qualifiedIdentifiers() {
    return Stream.of(
        Arguments.of("schema.table", "connector.t_c_bpartner", true),
        Arguments.of("a bare name, no dot", "t_c_bpartner", true),
        // Mirrors quoteTable, which splits on the FIRST dot: the second segment keeps the rest,
        // so a.b.c asks whether "b.c" is a plain identifier. It is not.
        Arguments.of("two dots", "a.b.c", false),
        Arguments.of("an empty schema", ".t_x", false),
        Arguments.of("an empty table", "connector.", false),
        Arguments.of("a dot alone", ".", false),
        Arguments.of("null", null, false),
        Arguments.of("a space in the table part", "connector.t x", false));
  }

  /**
   * The equivalence net: every BMP character, in every position it can occupy, must get the same
   * verdict from the predicate as from the {@link #ORACLE} regex.
   *
   * <p>The four shapes are not decoration — each catches a different plausible drift in a
   * hand-written loop: length one, where the loop body never runs; first character with a
   * successor; LAST character, which an {@code i < n - 1} off-by-one would skip; and the middle.
   *
   * <p>The whole BMP, not a sample, and lone surrogates included: {@code charAt} hands the loop
   * one half of a pair, which is exactly the shape a range test can get wrong. 65 536 code points
   * in four shapes is a quarter of a million matches and costs the suite tens of milliseconds,
   * which is a poor reason to leave a hole. An earlier draft stopped at U+0300 while the
   * production javadoc claimed the net was unbounded; the bound was arbitrary and the claim was
   * therefore false — this is the version that makes the sentence true.
   */
  @Test
  void the_predicate_agrees_with_its_regex_on_every_bmp_character_in_every_position() {
    List<String> disagreements = new ArrayList<>();
    for (int cp = 0; cp < 0x10000; cp++) {
      char c = (char) cp;
      for (String s : List.of(String.valueOf(c), "a" + c, c + "b", "a" + c + "b")) {
        boolean oracle = ORACLE.matcher(s).matches();
        if (SqlIdentifiers.isPlainIdentifier(s) != oracle) {
          disagreements.add(String.format("U+%04X in \"%s\" (regex says %s)", cp, s, oracle));
        }
      }
    }
    assertThat(disagreements)
        .as("the predicate and the regex that specifies it must never disagree")
        .isEmpty();
  }

  /**
   * What the qualified predicate APPROVES, {@code quoteTable} must render as plain segments only.
   *
   * <p>That is the invariant the class header calls "a SQL-injection surface, not a style nit":
   * the predicate approves an fqn, the renderer splits it again on its own, and nothing but a
   * sentence in a javadoc ("mirroring {@code quoteTable}") tied the two splits together. If the
   * renderer's boundary ever moves, an approved name is quoted as a different pair — approval
   * would then mean nothing about what reaches the DDL.
   *
   * <p>The first version of this test asserted the two SPLITS agree, which sounds stronger and
   * proves less. Neutralised — {@code lastIndexOf} in the predicate, the renderer untouched — it
   * stayed green, because no fqn can tell the two splits apart by verdict: two dots or more and
   * either boundary leaves a dot in a segment, so both refuse; one dot and the two are the same
   * index. The predicate is insensitive to which dot it picks. Only the RENDERER can drift, so
   * the assertion is on the renderer's output, which is what a neutralised {@code quoteTable}
   * does fail.
   */
  @Test
  void an_approved_qualified_name_is_rendered_as_plain_segments_only() {
    for (String fqn : List.of("connector.t_c_bpartner", "t_x", "a.b.c", ".t_x", "connector.",
        ".", "a..b", "connector.t x", "connector.t-cust", "schema.a.b")) {
      String[] parts = fqn.split("\\.", 2);
      String rendered = parts.length == 2
          ? SqlIdentifiers.quoteIdent(parts[0]) + "." + SqlIdentifiers.quoteIdent(parts[1])
          : SqlIdentifiers.quoteIdent(parts[0]);

      assertThat(SqlIdentifiers.quoteTable(fqn))
          .as("'%s': the renderer splits where the predicate assumes it does", fqn)
          .isEqualTo(rendered);

      if (SqlIdentifiers.isPlainQualifiedIdentifier(fqn)) {
        for (String part : parts) {
          assertThat(SqlIdentifiers.isPlainIdentifier(part))
              .as("'%s' was approved, so every segment the renderer quotes must be plain — "
                  + "segment '%s' is not", fqn, part)
              .isTrue();
        }
      }
    }
  }
}
