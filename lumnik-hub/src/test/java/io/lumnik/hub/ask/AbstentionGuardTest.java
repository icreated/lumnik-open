package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbstentionGuardTest {

  private final AbstentionGuard guard = new AbstentionGuard();

  private static SchemaCard normalCard() {
    return new SchemaCard("s", List.of(new SchemaCard.Table("connector.v", "v", List.of(
        new SchemaCard.Column("code", "text", "code")))));
  }

  @Test
  void hasNoSchema_true_for_null_no_tables_and_all_empty_columns() {
    assertThat(guard.hasNoSchema(null)).isTrue();
    assertThat(guard.hasNoSchema(new SchemaCard("s", List.of()))).isTrue();
    assertThat(guard.hasNoSchema(new SchemaCard("s", List.of(
        new SchemaCard.Table("connector.v", "v", List.of()))))).isTrue();
  }

  @Test
  void hasNoSchema_false_for_a_real_card() {
    assertThat(guard.hasNoSchema(normalCard())).isFalse();
  }

  @Test
  void isAbstention_true_for_the_sentinel_in_any_case_or_whitespace() {
    assertThat(guard.isAbstention("NO_ANSWER")).isTrue();
    assertThat(guard.isAbstention("  no_answer  ")).isTrue();
    assertThat(guard.isAbstention("NO_ANSWER\n")).isTrue();
  }

  @Test
  void isAbstention_false_for_sql_a_mere_mention_null_or_blank() {
    assertThat(guard.isAbstention("SELECT * FROM connector.v")).isFalse();
    assertThat(guard.isAbstention("SELECT no_answer FROM connector.v")).isFalse(); // a column named no_answer
    assertThat(guard.isAbstention(null)).isFalse();
    assertThat(guard.isAbstention("   ")).isFalse();
  }
}
