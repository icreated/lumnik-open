package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2062: tenant 1 brought {@code margin} to a hub table tenant 7 shares; tenant 7's card lists {@code code} and
 * {@code label}. Whatever SQL the model writes, the query tenant 7's Ask runs never shows {@code margin} — not through
 * {@code *}, not through {@code row_to_json} — and naming it is the database's own "does not exist". The SQL the answer
 * carries is the one that ran.
 */
@QuarkusTest
class AskHubTableProjectionIT {

  private static final String TABLE = "t_proj2062";

  @Inject AskToSqlAgent agent;
  @Inject SchemaCardStore store;
  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @InjectMock @Analytic ChatPort chatPort;

  @BeforeEach
  void seed() throws Exception {
    EntitySourceTableFixture.create(adminDs, TABLE, "v_none2062", "code", "label", "margin");
    exec("INSERT INTO connector." + TABLE + " VALUES (7, 'A-1', 'mine', NULL)");   // the columns, not the rows, are under test
    store.save(7L, "proj", new SchemaCard("proj", List.of(new SchemaCard.Table("connector." + TABLE, TABLE, List.of(
        new SchemaCard.Column("code", "text", "code"), new SchemaCard.Column("label", "text", "label"))))));
  }

  @AfterEach
  void clean() throws Exception {
    store.delete(7L, "proj");
    exec("DROP TABLE IF EXISTS connector." + TABLE);
  }

  @Test
  void a_star_shows_the_cards_columns_only() {
    AskResult r = ask("SELECT * FROM connector." + TABLE);
    assertThat(r.rows()).singleElement().satisfies(row -> assertThat(row).containsOnlyKeys("code", "label"));
    assertThat(r.sql()).as("the SQL that ran").contains("(SELECT \"code\", \"label\" FROM connector.\"" + TABLE + "\")");
  }

  @Test
  void a_whole_row_as_json_shows_the_cards_columns_only() {
    AskResult r = ask("SELECT row_to_json(t)::text AS j FROM connector." + TABLE + " t");
    assertThat(r.rows()).singleElement().satisfies(row -> assertThat(row.get("j").toString()).doesNotContain("margin"));
  }

  @Test
  void naming_a_column_off_the_card_reads_as_one_that_does_not_exist() {
    AskResult r = ask("SELECT margin FROM connector." + TABLE);
    assertThat(r.rows()).isEmpty();
    assertThat(r.answer()).contains("does not exist");
  }

  @Test
  void a_shape_it_cannot_read_runs_nothing() {
    AskResult r = ask("SELECT connector." + TABLE + ".code FROM connector." + TABLE);
    assertThat(r.rows()).isEmpty();
    assertThat(r.abstained()).isTrue();
  }

  private AskResult ask(String sql) {
    Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(sql, "Voici.");
    return agent.ask(7L, "proj", "montre tout");
  }

  private void exec(String... sql) throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      for (String s : sql) st.execute(s);
    }
  }
}
