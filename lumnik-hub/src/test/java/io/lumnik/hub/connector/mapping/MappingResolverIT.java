package io.lumnik.hub.connector.mapping;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link MappingResolverImpl} — template-only and template+override
 * merge paths.
 *
 * <p>Uses the admin datasource directly to insert/clean up override rows, bypassing RLS.
 * Each test uses a unique template ID (suffix from nanoTime) to avoid inter-test
 * interference; inserted rows are removed in {@link #cleanup()}.
 */
@QuarkusTest
class MappingResolverIT {

  @Inject MappingResolverImpl resolver;
  @Inject MappingTemplateRegistry templates;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  /** Tracks template IDs whose overrides were saved so we can clean up after each test. */
  private final List<String> insertedTemplateIds = new ArrayList<>();

  @AfterEach
  void cleanup() {
    if (insertedTemplateIds.isEmpty()) return;
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "DELETE FROM connector.connector_mapping WHERE template_id = ANY(?)")) {
      ps.setArray(1, c.createArrayOf("text", insertedTemplateIds.toArray()));
      ps.executeUpdate();
    } catch (Exception e) {
      // best-effort cleanup; isolated IDs so no cross-test pollution
    }
    insertedTemplateIds.clear();
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void resolves_template_only_when_no_override() {
    String tid = "t-template-only-" + System.nanoTime();
    templates.register(tid, """
        id: %s
        version: 1
        target: foo
        """.formatted(tid));

    MappingSpec spec = resolver.resolve(tid, 999L);

    assertThat(spec.id()).isEqualTo(tid);
    assertThat(spec.target()).isEqualTo("foo");
  }

  @Test
  void unknown_template_throws() {
    String tid = "nope-" + System.nanoTime();
    assertThatThrownBy(() -> resolver.resolve(tid, 1L))
        .isInstanceOf(MappingValidationException.class)
        .hasMessageContaining("unknown template");
  }

  @Test
  void merges_override_replacing_cell_section() {
    String tid = "t-merge-" + System.nanoTime();
    templates.register(tid, """
        id: %s
        version: 1
        target: foo
        cell:
          - clean:
              columns: ['*']
              strip: true
        """.formatted(tid));

    String overrideYaml = """
        id: %s
        version: 1
        target: foo
        cell:
          - parse-email:
              column: email
        """.formatted(tid);

    resolver.saveOverride(1L, tid, overrideYaml);
    insertedTemplateIds.add(tid);

    MappingSpec spec = resolver.resolve(tid, 1L);

    assertThat(spec.cell()).hasSize(1);
    assertThat(spec.cell().get(0).name()).isEqualTo("parse-email");
  }

  @Test
  void template_sections_preserved_when_not_overridden() {
    String tid = "t-preserve-" + System.nanoTime();
    templates.register(tid, """
        id: %s
        version: 1
        target: bar
        row:
          - map-columns:
              name: first_name
        cell:
          - clean:
              columns: ['*']
              strip: true
        """.formatted(tid));

    // override only touches the cell section
    String overrideYaml = """
        id: %s
        version: 1
        target: bar
        cell:
          - lower:
              column: email
        """.formatted(tid);

    resolver.saveOverride(2L, tid, overrideYaml);
    insertedTemplateIds.add(tid);

    MappingSpec spec = resolver.resolve(tid, 2L);

    // cell replaced
    assertThat(spec.cell()).hasSize(1);
    assertThat(spec.cell().get(0).name()).isEqualTo("lower");
    // row kept from template
    assertThat(spec.row()).hasSize(1);
    assertThat(spec.row().get(0).name()).isEqualTo("map-columns");
  }

  @Test
  void different_tenants_get_their_own_override() {
    String tid = "t-isolation-" + System.nanoTime();
    templates.register(tid, """
        id: %s
        version: 1
        target: baz
        cell:
          - clean:
              columns: ['*']
              strip: true
        """.formatted(tid));

    // tenant 10 overrides cell with upper
    resolver.saveOverride(10L, tid, """
        id: %s
        version: 1
        target: baz
        cell:
          - upper:
              column: code
        """.formatted(tid));

    // tenant 11 overrides cell with lower
    resolver.saveOverride(11L, tid, """
        id: %s
        version: 1
        target: baz
        cell:
          - lower:
              column: code
        """.formatted(tid));

    insertedTemplateIds.add(tid);

    assertThat(resolver.resolve(tid, 10L).cell().get(0).name()).isEqualTo("upper");
    assertThat(resolver.resolve(tid, 11L).cell().get(0).name()).isEqualTo("lower");
    // tenant with no override sees template
    assertThat(resolver.resolve(tid, 999L).cell().get(0).name()).isEqualTo("clean");
  }
}
