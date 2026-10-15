package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #622's Ask/RAG surface: {@code _entity_id}/{@code _resolution} are already computed columns on
 * the compiled view (#618, {@code EntityViewCompiler.appendResolvedSourceBranch}) but were never
 * declared on the {@code SchemaCard} — unlike {@code _conflicts} (a jsonb blob deliberately kept
 * off the card, per {@code AbstentionGuard}'s own doctrine), {@code _resolution} is a plain scalar
 * enum, safe to expose with a declared domain so the model can honestly answer "how many could
 * not be resolved" instead of guessing or omitting the question.
 *
 * <p>The domain must say {@code _resolution} can be NULL before the first pass — Sergey's review
 * note: a query grounded only on the four known states would silently omit rows still waiting for
 * a pass, understating "how many were not resolved" rather than answering it honestly.
 */
@QuarkusTest
class EntitySchemaBuilderResolutionIT {

  @Inject EntitySchemaBuilder builder;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  private static final String RESOLVABLE = """
      kind: Entity
      name: Res622Card
      scopes: [res622scope]
      sources:
        - table: t_res622_card_erp
          map: { code: customer_id, mail: email }
      resolution:
        identity:
          t_res622_card_erp: customer_id
        cascade:
          - id: email
            exact: { fields: [email] }
      """;

  private static final String MATCH_ONLY = """
      kind: Entity
      name: Res622CardMatch
      scopes: [res622scope]
      match: [customer_id]
      sources:
        - table: t_res622_card_erp2
          map: { code: customer_id, mail: email }
      """;

  @BeforeEach
  void seed() {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DELETE FROM connector.entity_manifest WHERE name IN ('res622card', 'res622cardmatch')");
      // EntityRegistry is a shared, application-scoped bean: once loadFromDb() picks up this
      // resolution-bearing manifest (triggered by ANY test's cache miss, not necessarily this
      // class's own), EntityResolutionPass.runIncremental fans out over it from every other test
      // class for the rest of the suite. A real (if empty) backing table — same posture as
      // EntityResolutionRunHookTest's own t_res_it620_hook_erp — keeps that fan-out harmless
      // instead of throwing "relation does not exist".
      st.execute("CREATE TABLE IF NOT EXISTS connector.t_res622_card_erp ("
          + "id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, _run_id uuid, code text, mail text)");
    } catch (Exception e) { throw new RuntimeException(e); }
    store("res622card", RESOLVABLE);
    store("res622cardmatch", MATCH_ONLY);
  }

  private void store(String name, String manifest) {
    String sql = "INSERT INTO connector.entity_manifest (name, yaml_text, scopes) "
        + "VALUES (?, ?, ARRAY['res622scope']::text[])";
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, name);
      ps.setString(2, manifest);
      ps.executeUpdate();
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  private SchemaCard.Table tableNamed(String viewName) {
    return builder.resolve("res622scope").stream()
        .filter(t -> t.name().equals(viewName)).findFirst().orElseThrow();
  }

  private static Optional<SchemaCard.Column> column(SchemaCard.Table t, String name) {
    return t.columns().stream().filter(c -> c.name().equals(name)).findFirst();
  }

  @Test
  void a_resolution_bearing_entity_declares_resolution_and_entity_id_with_the_null_caveat() {
    SchemaCard.Table table = tableNamed("connector.v_res622card");

    SchemaCard.Column resolution = column(table, "_resolution").orElseThrow();
    assertThat(resolution.pgType()).isEqualTo("text");
    assertThat(resolution.allowedValues())
        .containsExactlyInAnyOrder("resolved", "ambiguous", "unresolved_no_rule", "unresolved_provider");
    assertThat(resolution.description().toLowerCase())
        .as("must say NULL is a real, frequent state (before the first pass), not an edge case")
        .contains("null");

    SchemaCard.Column entityId = column(table, "_entity_id").orElseThrow();
    assertThat(entityId.pgType()).isEqualTo("uuid");
  }

  @Test
  void a_match_only_entity_declares_neither_column() {
    SchemaCard.Table table = tableNamed("connector.v_res622cardmatch");

    assertThat(column(table, "_resolution")).isEmpty();
    assertThat(column(table, "_entity_id")).isEmpty();
  }
}
