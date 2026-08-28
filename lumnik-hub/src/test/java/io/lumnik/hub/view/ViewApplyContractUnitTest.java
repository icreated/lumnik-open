package io.lumnik.hub.view;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.audit.AuditRecorder;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Curated writes: apply = validate-then-upsert. A view that cannot render is not
 * saved — the same parser + card gate as render. Integrator door, scope-bound too.
 */
class ViewApplyContractUnitTest {

  private static final String GOOD = """
      apiVersion: apps.lumnik.io/v1
      kind: View
      metadata:
        name: Clients-Desk
      spec:
        scope: clients
        table: connector.v_customer
        title: "Clients"
        columns: [customer_code]
      """;

  static final class RecordingStore extends SavedViewStore {
    String name, scope, title, yaml;
    @Override public void upsert(String name, String scope, String title, String yaml) {
      this.name = name; this.scope = scope; this.title = title; this.yaml = yaml;
    }
  }

  private final SchemaCardStore emptyCards = new SchemaCardStore() {
    @Override public Optional<SchemaCard> load(long tenantId, String scope) { return Optional.empty(); }
  };

  @BeforeEach
  void ctx() {
    CurrentTenantHolder.set(1L);
    CurrentRolesHolder.set(Set.of("lm_integrator", "lm_user"));
  }

  @AfterEach
  void clear() { CurrentTenantHolder.clear(); CurrentRolesHolder.clear(); }

  @Test
  void door_contract_integrator_and_own_path() {
    Path p = ViewApplyResource.class.getAnnotation(Path.class);
    assertThat(p.value()).isEqualTo("/api/views/apply");
    RequiresPermission perm = ViewApplyResource.class.getAnnotation(RequiresPermission.class);
    assertThat(perm.value()).containsExactly("lm_integrator");
  }

  @Test
  void invalid_yaml_is_400_and_nothing_saved() {
    RecordingStore store = new RecordingStore();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    Response r = res.apply("kind: Nonsense");
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(store.name).isNull();
  }

  /**
   * C3: an undeclared key used to no-op in silence — a misspelled {@code titel:} was accepted,
   * saved, and the title simply never appeared. The curated door refuses it and names it. The
   * refusal lands BEFORE the card lookup, so it is reachable without a SchemaCard.
   */
  @Test
  void an_unknown_key_is_400_named_and_nothing_saved() {
    RecordingStore store = new RecordingStore();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    res.cards = emptyCards;

    Response r = res.apply(GOOD.replace("title: \"Clients\"", "titel: \"Clients\""));

    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getEntity().toString())
        .as("the refusal must name the offending key, not just refuse")
        .contains("spec.titel");
    assertThat(r.getEntity().toString())
        .as("and it must name apiVersion as accepted — a teaching message that omits an "
            + "honored key is the same lie by omission this PR refuses in the YAML")
        .contains("apiVersion");
    assertThat(store.name).isNull();
  }

  static final class RecordingAudit extends AuditRecorder {
    String entityType, entityKey, operation;
    Map<String, Object> snapshot;
    @Override public void record(String entityType, String entityKey, String operation, Map<String, Object> snapshot) {
      this.entityType = entityType; this.entityKey = entityKey; this.operation = operation; this.snapshot = snapshot;
    }
  }

  private static final SchemaCardStore CLIENTS_CARD = new SchemaCardStore() {
    @Override public Optional<SchemaCard> load(long tenantId, String scope) {
      return Optional.of(new SchemaCard("clients", List.of(
          new SchemaCard.Table("connector.v_customer", "Clients", List.of(
              new SchemaCard.Column("customer_code", "text", "Code client"))))));
    }
  };

  @Test
  void a_successful_apply_is_recorded_in_the_audit_trail() {
    // Audit P4: 'who changed which métier screen when' was unrecoverable — SavedViewStore
    // upserts through raw JDBC, outside every Hibernate listener, and nothing recorded the
    // apply. The recorder must capture actor-attributable facts: name, scope, manifest hash.
    RecordingStore store = new RecordingStore();
    RecordingAudit audit = new RecordingAudit();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    res.cards = CLIENTS_CARD;
    res.audit = audit;

    Response r = res.apply(GOOD);

    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(audit.entityType).as("the apply must be audited").isEqualTo("SavedView");
    assertThat(audit.entityKey).isEqualTo("clients-desk");
    assertThat(audit.operation).isEqualTo("APPLY");
    assertThat(audit.snapshot).containsEntry("scope", "clients")
        .containsKey("manifest_sha256");
  }

  @Test
  void the_shipped_apiVersion_envelope_is_still_accepted() {
    // GOOD already carries `apiVersion: apps.lumnik.io/v1` — the house envelope the parser ignores.
    // If closing the root block ever refuses it, every manifest the project ships stops applying.
    RecordingStore store = new RecordingStore();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    res.cards = emptyCards;

    Response r = res.apply(GOOD);

    assertThat(r.getStatus()).as("404 = it got past the key check to the card lookup").isEqualTo(404);
  }

  @Test
  void unknown_scope_card_is_404_and_nothing_saved() {
    RecordingStore store = new RecordingStore();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    res.cards = emptyCards;
    Response r = res.apply(GOOD);
    assertThat(r.getStatus()).isEqualTo(404);
    assertThat(store.name).isNull();
  }

  @Test
  void bound_integrator_foreign_scope_is_403() {
    CurrentRolesHolder.set(Set.of("lm_integrator", "lm_user", "scope:stock"));
    RecordingStore store = new RecordingStore();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    Response r = res.apply(GOOD);
    assertThat(r.getStatus()).isEqualTo(403);
    assertThat(((Map<String,Object>) r.getEntity()).get("error")).isEqualTo("scope not granted");
    assertThat(store.name).isNull();
  }

  @Test
  void unsafe_name_is_400_and_nothing_saved() {
    // the name becomes a URL path segment in GET /api/views/{name} — a slash or space
    // would save a row that can never be fetched. Refuse loudly at the door.
    RecordingStore store = new RecordingStore();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    Response r = res.apply(GOOD.replace("name: Clients-Desk", "name: \"clients/desk\""));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(store.name).isNull();
  }

  @Test
  void reserved_name_is_400_and_nothing_saved() {
    // apply/render/detail/generate are sibling endpoint literals under /api/views —
    // a view named 'apply' would list but never fetch (routing shadows the template).
    RecordingStore store = new RecordingStore();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    Response r = res.apply(GOOD.replace("name: Clients-Desk", "name: Apply"));
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(store.name).isNull();
  }

  @Test
  void valid_view_upserts_lowercased_with_resolved_title() {
    RecordingStore store = new RecordingStore();
    ViewApplyResource res = new ViewApplyResource();
    res.store = store;
    res.audit = new RecordingAudit();
    res.cards = new SchemaCardStore() {
      @Override public Optional<SchemaCard> load(long tenantId, String scope) {
        return Optional.of(new SchemaCard(scope, List.of(new SchemaCard.Table(
            "connector.v_customer", "v_customer",
            List.of(new SchemaCard.Column("customer_code", "text", "Code client", null, null, null))))));
      }
    };
    Response r = res.apply(GOOD);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(store.name).isEqualTo("clients-desk");       // lowercased, entity_manifest rule
    assertThat(store.scope).isEqualTo("clients");
    assertThat(store.title).isEqualTo("Clients");
    assertThat(store.yaml).isEqualTo(GOOD);
    assertThat(((Map<String,Object>) r.getEntity())).containsEntry("name", "clients-desk");
  }
}
