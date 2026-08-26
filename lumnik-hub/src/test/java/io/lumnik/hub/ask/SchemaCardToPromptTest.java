package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;

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
    assertThat(p).contains("connector.v_customer  -- customer (A customer.)");
    assertThat(p).contains("segment text  -- Commercial segment [allowed: GOLD, SILVER, BRONZE]");
    assertThat(p).contains("city text  -- city [e.g. Lyon]");
  }

  @Test
  void backward_compatible_three_arg_column_renders_only_label_no_brackets() {
    SchemaCard card = new SchemaCard("s", List.of(
        new SchemaCard.Table("connector.t_x", "x", List.of(
            new SchemaCard.Column("a", "text", "Label A")))));
    String p = card.toPrompt();
    assertThat(p).contains("a text  -- Label A");
    assertThat(p).doesNotContain("[");
  }
}
