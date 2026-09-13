package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.sql.SqlIdentifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCardToPromptTest {

  @Test
  void renders_description_example_and_allowed_values() {
    SchemaCard card = new SchemaCard("clients", List.of(
        new SchemaCard.Table("connector.v_customer", "customer", List.of(
            new SchemaCard.Column("segment", "text", "segment",
                "Commercial segment", null, List.of("GOLD", "SILVER", "BRONZE")),
            new SchemaCard.Column("city", "text", "city", null, "Lyon", null)),
            "A customer.")));
    String p = card.toPrompt();
    assertThat(p).contains("\"connector\".\"v_customer\"  -- customer (A customer.)");
    assertThat(p).contains("\"segment\" text  -- Commercial segment [allowed: GOLD, SILVER, BRONZE]");
    assertThat(p).contains("\"city\" text  -- city [e.g. Lyon]");
  }

  @Test
  void backward_compatible_three_arg_column_renders_only_label_no_brackets() {
    SchemaCard card = new SchemaCard("s", List.of(
        new SchemaCard.Table("connector.t_x", "x", List.of(
            new SchemaCard.Column("a", "text", "Label A")))));
    String p = card.toPrompt();
    assertThat(p).contains("\"a\" text  -- Label A");
    assertThat(p).doesNotContain("[");
  }

  /**
   * Measured, not assumed, and with no recourse behind it: a kafka message's JSON keys become columns
   * verbatim and that connector has no mapping block to rename one. Rendered BARE, qwen2.5-coder:7b (the
   * shipped analytic model, temperature 0) wrote {@code SELECT c_bpartner.id …} — the dot is the
   * qualifier separator, so Postgres answered {@code 42P01 missing FROM-clause entry for table
   * "c_bpartner"}; it renamed {@code Partner Name} to {@code Partner_Name}, INVENTING a name its own
   * rules forbid, because the rendered line {@code Partner Name text} cannot be parsed by its reader;
   * and {@code MONTANT_€} case-folded to a column that does not exist. Three of three unaskable.
   * Rendered quoted, the same model quoted all three, 8 questions out of 8 — and on a plain card its
   * output was byte-identical, which is why the quoting is unconditional rather than a predicate.
   */
  @ParameterizedTest(name = "a column named {0} is rendered quoted")
  @ValueSource(strings = {"c_bpartner.id", "Partner Name", "MONTANT_€", "order"})
  void aNameTheModelCannotCopyBareIsRenderedQuoted(String name) {
    SchemaCard card = new SchemaCard("kafka", List.of(
        new SchemaCard.Table("connector.t_kafka_orders", "orders", List.of(
            new SchemaCard.Column(name, "text", name)))));

    assertThat(card.toPrompt())
        .as("bare, the model has nothing it can copy into SQL for %s", name)
        .contains("\"" + name + "\" text");
  }

  /**
   * The escaping now exists in two copies, and this is what keeps the claim on the twin true.
   * {@code SqlIdentifiers} calls itself "one source of truth so the escaping cannot drift between
   * copies"; the card renders prompt TEXT rather than a statement, and {@code lumnik-extraction-spi}
   * declares no dependency that could reach the hub, so a second copy there is the only legal shape.
   * The hub sees both — so it can pin them equal, including the case that actually needs escaping.
   */
  @ParameterizedTest(name = "{0} is quoted the way the hub''s own quoter would")
  @ValueSource(strings = {"documentno", "c_bpartner.id", "Partner Name", "he said \"hi\""})
  void theCardsQuotingDoesNotDriftFromTheHubsQuoter(String name) {
    SchemaCard card = new SchemaCard("s", List.of(
        new SchemaCard.Table("connector.t_x", "x", List.of(
            new SchemaCard.Column(name, "text", "label")))));

    assertThat(card.toPrompt()).contains(SqlIdentifiers.quoteIdent(name) + " text");
  }

  /**
   * The line ABOVE the columns has the same defect, and the door that was supposed to make quoting
   * unnecessary does not close it: {@code SqlIdentifiers.isPlainIdentifier} accepts {@code Orders},
   * nothing lowercases a manifest {@code name}, and the writer creates the relation with the case it
   * was given — so {@code connector.t_Orders} reaches the card. Measured on PostgreSQL 16 under the
   * empty {@code search_path} the ask path uses: bare, {@code SELECT * FROM connector.t_Orders} answers
   * {@code relation "connector.t_orders" does not exist}; quoted in both parts, it returns rows.
   * Split on the FIRST dot only, the same boundary {@code SqlIdentifiers.quoteTable} draws.
   */
  @Test
  void aMixedCaseTableIsRenderedQuotedInBothParts() {
    SchemaCard card = new SchemaCard("s", List.of(
        new SchemaCard.Table("connector.t_Orders", "Orders", List.of(
            new SchemaCard.Column("documentno", "text", "Document No")))));

    assertThat(card.toPrompt())
        .as("bare, the model copies a name the server case-folds into a relation that does not exist")
        .contains("TABLE \"connector\".\"t_Orders\"");
  }
}
